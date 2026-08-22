package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.AttributeDefinition;
import co.ara.onboarding.workflow.AttributeDefinitionRepository;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Computes migration eligibility and repins running cases onto a newer workflow
 * version. Every read here goes through AuthorizedQuery, under either
 * WORKFLOW_VIEW (workflow-definition entities, ALL-only, mirroring CaseService's
 * own readDefinition/readOneDefinition) or CASE_MIGRATE (the case rows and their
 * runtime children) -- never a repository finder called directly.
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly covers
 * co.ara.onboarding.journey.
 */
@Service
public class MigrationService {

    private final CaseRepository cases;
    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;
    private final CaseAttributeValueRepository attributeValues;
    private final WorkflowVersionRepository versions;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final AttributeDefinitionRepository attributeDefinitions;
    private final AuthorizedQuery authorizedQuery;
    private final AuditRecorder audit;
    private final CaseEngine engine;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public MigrationService(CaseRepository cases, MilestoneRepository milestones,
                            RequirementRepository requirements, CaseAttributeValueRepository attributeValues,
                            WorkflowVersionRepository versions, StageRepository stages,
                            MilestoneDefinitionRepository milestoneDefinitions,
                            RequirementDefinitionRepository requirementDefinitions,
                            AttributeDefinitionRepository attributeDefinitions,
                            AuthorizedQuery authorizedQuery, AuditRecorder audit, CaseEngine engine,
                            BusinessCalendar calendar, Clock clock) {
        this.cases = cases;
        this.milestones = milestones;
        this.requirements = requirements;
        this.attributeValues = attributeValues;
        this.versions = versions;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.requirementDefinitions = requirementDefinitions;
        this.attributeDefinitions = attributeDefinitions;
        this.authorizedQuery = authorizedQuery;
        this.audit = audit;
        this.engine = engine;
        this.calendar = calendar;
        this.clock = clock;
    }

    @RequirePermission(PermissionKeys.CASE_MIGRATE)
    @Transactional(readOnly = true)
    public MigrationPreviewView preview(UUID targetVersionId) {
        WorkflowVersion target = readOneDefinition(versions, WorkflowVersion.class, targetVersionId);
        List<Stage> targetStages = readDefinition(stages, Stage.class, targetVersionId, "ordinal");
        List<AttributeDefinition> targetAttributes = readDefinition(attributeDefinitions,
                AttributeDefinition.class, targetVersionId, "ordinal");

        List<Case> onOldVersion = casesOnAnOldVersionOf(target.getTemplateId(), targetVersionId);
        List<CandidateView> candidates = onOldVersion.stream()
                .map(c -> evaluate(c, targetStages, targetAttributes))
                .toList();
        int eligibleCount = (int) candidates.stream().filter(CandidateView::eligible).count();
        return new MigrationPreviewView(targetVersionId, onOldVersion.size(), eligibleCount, candidates);
    }

    @RequirePermission(PermissionKeys.CASE_MIGRATE)
    @Transactional
    public int migrate(UUID targetVersionId, List<UUID> caseIds) {
        List<Stage> targetStages = readDefinition(stages, Stage.class, targetVersionId, "ordinal");
        List<AttributeDefinition> targetAttributes = readDefinition(attributeDefinitions,
                AttributeDefinition.class, targetVersionId, "ordinal");
        Map<UUID, Stage> targetStageById = targetStages.stream().collect(toMap(Stage::getId, s -> s));
        List<MilestoneDefinition> targetMilestoneDefs = readDefinition(milestoneDefinitions,
                MilestoneDefinition.class, targetVersionId, "ordinal");
        Map<String, MilestoneDefinition> targetMilestoneDefByKey = targetMilestoneDefs.stream()
                .collect(toMap(d -> key(targetStageById.get(d.getStageId()).getName(), d.getName()), d -> d));
        Map<UUID, List<RequirementDefinition>> targetRequirementDefsByMilestoneDef = readDefinition(
                        requirementDefinitions, RequirementDefinition.class, targetVersionId, "ordinal")
                .stream().collect(groupingBy(RequirementDefinition::getMilestoneDefinitionId));

        int migrated = 0;
        for (UUID caseId : caseIds) {
            authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_MIGRATE, caseId);
            Case c = engine.lockAndLoad(caseId);

            CandidateView candidate = evaluate(c, targetStages, targetAttributes);
            if (!candidate.eligible()) {
                throw new CaseNotMigratableException(caseId, candidate.reason());
            }
            migrateOne(c, targetVersionId, targetStages, targetMilestoneDefByKey, targetRequirementDefsByMilestoneDef);
            migrated++;
        }
        return migrated;
    }

    /**
     * A case is eligible when nothing it has already done would be orphaned, and
     * nothing the new version demands is missing.
     *
     * Deliberately conservative. The alternative -- migrating and repairing -- would
     * rewrite history for completed milestones, and the whole point of Q2's
     * freeze-by-default is that a running case's definition does not change under it
     * without someone deciding so.
     */
    private CandidateView evaluate(Case c, List<Stage> targetStages, List<AttributeDefinition> targetAttributes) {
        List<Stage> ownStages = readDefinition(stages, Stage.class, c.getVersionId(), "ordinal");
        Set<String> targetStageNames = targetStages.stream().map(Stage::getName).collect(toSet());
        String currentStageName = currentStageNameOf(c, ownStages);

        // Stage identity across versions is the NAME, because a new version's rows
        // are new ids: a deep copy produces different primary keys for the same
        // stage. Name is what the builder shows and what an admin reasons about
        // when they reorder.
        for (Stage passed : stagesAlreadyPassed(c, ownStages)) {
            if (!targetStageNames.contains(passed.getName())) {
                return new CandidateView(c.getId(), c.getCustomerId(), currentStageName, false,
                        "Stage '" + passed.getName()
                                + "' has already been completed but no longer exists in the new version");
            }
        }

        List<AttributeDefinition> ownAttributes = readDefinition(attributeDefinitions,
                AttributeDefinition.class, c.getVersionId(), "ordinal");
        Map<UUID, String> ownAttributeKeyById = ownAttributes.stream()
                .collect(toMap(AttributeDefinition::getId, AttributeDefinition::getKey));
        Set<String> present = readCaseChild(attributeValues, CaseAttributeValue.class, c.getId()).stream()
                .map(v -> ownAttributeKeyById.get(v.getAttributeDefinitionId()))
                .filter(Objects::nonNull)
                .collect(toSet());
        for (AttributeDefinition required : targetAttributes) {
            if (required.isRequired() && !present.contains(required.getKey())) {
                return new CandidateView(c.getId(), c.getCustomerId(), currentStageName, false,
                        "The new version requires attribute '" + required.getKey()
                                + "', which this case has no value for");
            }
        }
        return new CandidateView(c.getId(), c.getCustomerId(), currentStageName, true, null);
    }

    /** Stages this case has fully exited: every one with an ordinal below the current stage's. */
    private List<Stage> stagesAlreadyPassed(Case c, List<Stage> ownStages) {
        if (c.getCurrentStageId() == null) return List.of();
        Stage current = ownStages.stream().filter(s -> s.getId().equals(c.getCurrentStageId()))
                .findFirst().orElse(null);
        if (current == null) return List.of();
        return ownStages.stream().filter(s -> s.getOrdinal() < current.getOrdinal()).toList();
    }

    private String currentStageNameOf(Case c, List<Stage> ownStages) {
        if (c.getCurrentStageId() == null) return null;
        return ownStages.stream().filter(s -> s.getId().equals(c.getCurrentStageId()))
                .findFirst().map(Stage::getName).orElse(null);
    }

    /**
     * Repins versionId, remaps every surviving milestone (and its requirements) onto
     * the target version's matching definitions by name, instantiates whatever the
     * new version adds, and skips whatever it drops that this case had not yet
     * reached. Orphaned, not deleted -- DELETE is denied on journey tables and the
     * record that the case once expected that work is worth keeping.
     */
    private void migrateOne(Case c, UUID targetVersionId, List<Stage> targetStages,
                            Map<String, MilestoneDefinition> targetMilestoneDefByKey,
                            Map<UUID, List<RequirementDefinition>> targetRequirementDefsByMilestoneDef) {
        List<Stage> ownStages = readDefinition(stages, Stage.class, c.getVersionId(), "ordinal");
        Map<UUID, Stage> ownStageById = ownStages.stream().collect(toMap(Stage::getId, s -> s));
        Map<UUID, MilestoneDefinition> ownMilestoneDefById = readDefinition(milestoneDefinitions,
                        MilestoneDefinition.class, c.getVersionId(), "ordinal")
                .stream().collect(toMap(MilestoneDefinition::getId, d -> d));
        Map<UUID, RequirementDefinition> ownRequirementDefById = readDefinition(requirementDefinitions,
                        RequirementDefinition.class, c.getVersionId(), "ordinal")
                .stream().collect(toMap(RequirementDefinition::getId, d -> d));

        String oldCurrentStageName = currentStageNameOf(c, ownStages);
        List<Milestone> caseMilestones = readCaseChild(milestones, Milestone.class, c.getId());
        Set<UUID> matchedTargetDefIds = new HashSet<>();

        for (Milestone m : caseMilestones) {
            MilestoneDefinition oldDef = ownMilestoneDefById.get(m.getMilestoneDefinitionId());
            if (oldDef == null) continue;
            Stage oldStage = ownStageById.get(oldDef.getStageId());
            MilestoneDefinition newDef = targetMilestoneDefByKey.get(key(oldStage.getName(), oldDef.getName()));

            if (newDef == null) {
                if (m.getStatus() != MilestoneStatus.DONE && m.getStatus() != MilestoneStatus.SKIPPED) {
                    m.setStatus(MilestoneStatus.SKIPPED);
                    milestones.save(m);
                    audit.record(AuditActions.MILESTONE_SKIPPED, "milestone", m.getId(),
                            "Skipped milestone \"" + oldDef.getName() + "\" (removed by migration)",
                            Map.of("caseId", c.getId().toString()));
                }
                continue;
            }
            matchedTargetDefIds.add(newDef.getId());
            m.setMilestoneDefinitionId(newDef.getId());
            milestones.save(m);
            remapRequirements(c, m, ownRequirementDefById,
                    targetRequirementDefsByMilestoneDef.getOrDefault(newDef.getId(), List.of()));
        }

        List<Milestone> instantiated = new ArrayList<>();
        for (MilestoneDefinition newDef : targetMilestoneDefByKey.values()) {
            if (matchedTargetDefIds.contains(newDef.getId())) continue;
            Milestone m = new Milestone();
            m.setId(Uuid7.generate());
            m.setTenantId(c.getTenantId());
            m.setCaseId(c.getId());
            m.setMilestoneDefinitionId(newDef.getId());
            m.setStatus(MilestoneStatus.PENDING);
            m.setOwnerUserId(c.getOwnerUserId());
            m.setProgressPercent(0);
            milestones.save(m);
            instantiated.add(m);

            for (RequirementDefinition rd : targetRequirementDefsByMilestoneDef.getOrDefault(newDef.getId(), List.of())) {
                Requirement r = new Requirement();
                r.setId(Uuid7.generate());
                r.setTenantId(c.getTenantId());
                r.setCaseId(c.getId());
                r.setMilestoneId(m.getId());
                r.setRequirementDefinitionId(rd.getId());
                r.setStatus(RequirementStatus.OPEN);
                requirements.save(r);
            }
        }

        c.setVersionId(targetVersionId);

        // The current stage was already entered under the old version, so
        // CaseEngine.reconcile's enterStage (which is what normally sets due dates)
        // never runs for it again -- recompute its OPEN milestones' due dates here,
        // against the new durations, the same cumulative-business-days rule
        // enterStage itself uses.
        if (oldCurrentStageName != null) {
            targetStages.stream().filter(s -> s.getName().equals(oldCurrentStageName)).findFirst()
                    .ifPresent(newCurrentStage -> {
                        c.setCurrentStageId(newCurrentStage.getId());
                        List<Milestone> all = new ArrayList<>(caseMilestones);
                        all.addAll(instantiated);
                        recomputeCurrentStageDueDates(newCurrentStage, all, targetMilestoneDefByKey);
                        c.setTargetCompletionDate(latestDueDate(all));
                    });
        }

        engine.reconcile(c);

        audit.record(AuditActions.CASE_MIGRATED, "onboarding_case", c.getId(),
                "Migrated to a newer workflow version", Map.of("versionId", targetVersionId.toString()));
    }

    private void remapRequirements(Case c, Milestone m, Map<UUID, RequirementDefinition> ownRequirementDefById,
                                   List<RequirementDefinition> targetRequirementDefsForMilestone) {
        Map<String, RequirementDefinition> targetByLabel = targetRequirementDefsForMilestone.stream()
                .collect(toMap(RequirementDefinition::getLabel, d -> d, (a, b) -> a));
        Set<UUID> matchedTargetReqDefIds = new HashSet<>();

        for (Requirement r : readMilestoneRequirements(m.getId())) {
            RequirementDefinition oldReqDef = ownRequirementDefById.get(r.getRequirementDefinitionId());
            if (oldReqDef == null) continue;
            RequirementDefinition newReqDef = targetByLabel.get(oldReqDef.getLabel());
            if (newReqDef == null) continue;   // dropped requirement: left as-is, ignored by future computations
            matchedTargetReqDefIds.add(newReqDef.getId());
            r.setRequirementDefinitionId(newReqDef.getId());
            requirements.save(r);
        }

        for (RequirementDefinition newReqDef : targetRequirementDefsForMilestone) {
            if (matchedTargetReqDefIds.contains(newReqDef.getId())) continue;
            Requirement r = new Requirement();
            r.setId(Uuid7.generate());
            r.setTenantId(c.getTenantId());
            r.setCaseId(c.getId());
            r.setMilestoneId(m.getId());
            r.setRequirementDefinitionId(newReqDef.getId());
            r.setStatus(RequirementStatus.OPEN);
            requirements.save(r);
        }
    }

    private void recomputeCurrentStageDueDates(Stage newCurrentStage, List<Milestone> allMilestones,
                                               Map<String, MilestoneDefinition> targetMilestoneDefByKey) {
        List<MilestoneDefinition> stageDefs = targetMilestoneDefByKey.values().stream()
                .filter(d -> d.getStageId().equals(newCurrentStage.getId()))
                .sorted(Comparator.comparingInt(MilestoneDefinition::getOrdinal))
                .toList();
        LocalDate cursor = LocalDate.now(clock);
        int cumulative = 0;
        for (MilestoneDefinition def : stageDefs) {
            cumulative += def.getEstimatedDurationDays();
            Milestone m = allMilestones.stream()
                    .filter(x -> def.getId().equals(x.getMilestoneDefinitionId())).findFirst().orElse(null);
            if (m == null || m.getStatus() == MilestoneStatus.DONE || m.getStatus() == MilestoneStatus.SKIPPED) continue;
            m.setDueDate(calendar.plusBusinessDays(cursor, cumulative));
            milestones.save(m);
        }
    }

    private LocalDate latestDueDate(List<Milestone> instances) {
        return instances.stream().map(Milestone::getDueDate).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private List<Case> casesOnAnOldVersionOf(UUID templateId, UUID targetVersionId) {
        Specification<Case> spec = (root, query, cb) -> cb.and(
                cb.equal(root.get("templateId"), templateId),
                cb.notEqual(root.get("versionId"), targetVersionId),
                cb.not(root.get("status").in(List.of(CaseStatus.COMPLETED, CaseStatus.CANCELLED))));
        return authorizedQuery.findAll(cases, Case.class, PermissionKeys.CASE_MIGRATE, spec, Pageable.unpaged())
                .getContent();
    }

    private List<Requirement> readMilestoneRequirements(UUID milestoneId) {
        Specification<Requirement> byMilestone = (root, query, cb) -> cb.equal(root.get("milestoneId"), milestoneId);
        return authorizedQuery.findAll(requirements, Requirement.class, PermissionKeys.CASE_MIGRATE,
                        byMilestone, Pageable.unpaged())
                .getContent();
    }

    private static String key(String stageName, String milestoneName) {
        return stageName + " " + milestoneName;
    }

    /** Reads a workflow-definition entity by version, always under WORKFLOW_VIEW -- see CaseService's own javadoc. */
    private <T> List<T> readDefinition(JpaSpecificationExecutor<T> repo, Class<T> type,
                                       UUID versionId, String sortField) {
        Specification<T> byVersion = (root, query, cb) -> cb.equal(root.get("versionId"), versionId);
        return authorizedQuery.findAll(repo, type, PermissionKeys.WORKFLOW_VIEW, byVersion,
                        Pageable.unpaged(Sort.by(sortField)))
                .getContent();
    }

    private <T> T readOneDefinition(JpaSpecificationExecutor<T> repo, Class<T> type, UUID id) {
        return authorizedQuery.getById(repo, type, PermissionKeys.WORKFLOW_VIEW, id);
    }

    /** Reads a journey runtime entity scoped to one already-authorized case, under CASE_MIGRATE. */
    private <T> List<T> readCaseChild(JpaSpecificationExecutor<T> repo, Class<T> type, UUID caseId) {
        Specification<T> byCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        return authorizedQuery.findAll(repo, type, PermissionKeys.CASE_MIGRATE, byCase, Pageable.unpaged())
                .getContent();
    }
}
