package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.Department;
import co.ara.onboarding.identity.DepartmentRepository;
import co.ara.onboarding.identity.Team;
import co.ara.onboarding.identity.TeamRepository;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.workflow.AttributeDefinition;
import co.ara.onboarding.workflow.AttributeDefinitionRepository;
import co.ara.onboarding.workflow.AttributeType;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.MilestoneDependency;
import co.ara.onboarding.workflow.MilestoneDependencyRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import co.ara.onboarding.workflow.WorkflowTemplate;
import co.ara.onboarding.workflow.WorkflowTemplateRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Opens and edits cases. Every read of a workflow definition entity (Stage,
 * MilestoneDefinition, RequirementDefinition, AttributeDefinition, WorkflowTemplate,
 * WorkflowVersion) goes through {@link #readDefinition} / {@link #readOneDefinition},
 * under WORKFLOW_VIEW, which is ALL-only (PermissionCatalog) -- never a direct
 * repository finder, and never a descriptor, because none of those five entities has
 * one and none needs one while the permission stays ALL-only. Every read of a runtime
 * journey entity (Case, Milestone, Requirement, CaseParticipant, CaseAttributeValue)
 * goes through AuthorizedQuery under the case-scoped permission the calling method is
 * itself gated on, resolved by CaseDescriptor/MilestoneDescriptor/RequirementDescriptor/
 * CaseParticipantDescriptor/CaseAttributeValueDescriptor. AuthorizationCoverageTest.
 * servicesDoNotCallRepositoryFindersDirectly is what makes both halves of that a rule
 * rather than a habit.
 */
@Service
public class CaseService {

    private final CaseRepository cases;
    private final CaseParticipantRepository participants;
    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;
    private final CaseAttributeValueRepository attributeValues;
    private final CustomerDirectory customers;
    private final WorkflowTemplateRepository templates;
    private final WorkflowVersionRepository versions;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final MilestoneDependencyRepository milestoneDependencies;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final AttributeDefinitionRepository attributeDefinitions;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final CaseEngine engine;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public CaseService(CaseRepository cases, CaseParticipantRepository participants,
                       MilestoneRepository milestones, RequirementRepository requirements,
                       CaseAttributeValueRepository attributeValues, CustomerDirectory customers,
                       WorkflowTemplateRepository templates, WorkflowVersionRepository versions,
                       StageRepository stages, MilestoneDefinitionRepository milestoneDefinitions,
                       MilestoneDependencyRepository milestoneDependencies,
                       RequirementDefinitionRepository requirementDefinitions,
                       AttributeDefinitionRepository attributeDefinitions,
                       AppUserRepository users, DepartmentRepository departments,
                       TeamRepository teams, AuthorizedQuery authorizedQuery,
                       AuthContextProvider contextProvider, AuditRecorder audit, CaseEngine engine,
                       BusinessCalendar calendar, Clock clock) {
        this.cases = cases;
        this.participants = participants;
        this.milestones = milestones;
        this.requirements = requirements;
        this.attributeValues = attributeValues;
        this.customers = customers;
        this.templates = templates;
        this.versions = versions;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.milestoneDependencies = milestoneDependencies;
        this.requirementDefinitions = requirementDefinitions;
        this.attributeDefinitions = attributeDefinitions;
        this.users = users;
        this.departments = departments;
        this.teams = teams;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.engine = engine;
        this.calendar = calendar;
        this.clock = clock;
    }

    @RequirePermission(PermissionKeys.CASE_CREATE)
    @Transactional
    public CaseView create(CreateCaseRequest request) {
        // The customer is resolved through the port, which applies the caller's own
        // customer.view scope. case.create being ALL-only is safe precisely because of
        // this line: authority to create is bounded by which customers you can see.
        CustomerFacts customer = customers.findVisible(request.customerId())
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        WorkflowTemplate template = readOneDefinition(templates, WorkflowTemplate.class, request.templateId());
        if (template.getCurrentVersionId() == null) {
            throw new TemplateNotPublishedException(template.getId());
        }
        UUID versionId = template.getCurrentVersionId();

        List<AttributeDefinition> declared = readDefinition(attributeDefinitions,
                AttributeDefinition.class, versionId, "ordinal");
        validateAttributes(declared, request.attributes());

        Case c = new Case();
        c.setId(Uuid7.generate());
        c.setTenantId(TenantContext.getRequired());
        c.setCustomerId(customer.id());
        c.setTemplateId(template.getId());
        c.setVersionId(versionId);                 // pinned here, never reassigned except by migration
        c.setStatus(CaseStatus.ACTIVE);
        c.setStartedAt(Instant.now());
        c.setProgressPercent(0);
        c.setTotalHoldDays(0);
        // Copied, not joined: these three columns are what CaseDescriptor resolves
        // DEPARTMENT and TEAM against.
        c.setOwnerUserId(customer.ownerUserId());
        c.setOwningDepartmentId(customer.owningDepartmentId());
        c.setOwningTeamId(customer.owningTeamId());
        c.setCreatedBy(contextProvider.principal().userId());
        // Reassigned, not discarded: BaseEntity has no @Version and no Persistable,
        // so Spring Data's isNew() sees this already-non-null Uuid7 id and calls
        // entityManager.merge() rather than persist(). merge() returns a DIFFERENT
        // managed instance and leaves the original detached -- every mutation below
        // (writeParticipant/upsertAttributes/instantiate/engine.reconcile) would be
        // silently lost from the database, even though the CaseView this method
        // returns (built from the detached, in-memory-mutated object) looks correct.
        // Only surfaced once Task 15 added a caller (advance()) that re-reads the
        // case in a LATER transaction; every read inside this same transaction
        // happened to resolve back to the same identity-mapped instance regardless.
        c = cases.save(c);

        // The creator is a CREATOR participant, which is recorded but confers no
        // ASSIGNED access (CaseDescriptor excludes it). The customer's owner is the
        // OWNER participant and, per Q15, every milestone's default owner.
        writeParticipant(c, contextProvider.principal().userId(), RelationshipType.CREATOR);
        if (customer.ownerUserId() != null) {
            writeParticipant(c, customer.ownerUserId(), RelationshipType.OWNER);
        }

        upsertAttributes(c, declared, request.attributes());
        instantiate(c, versionId, customer.ownerUserId());

        // Recorded BEFORE reconcile, which writes case.stage_entered and any
        // milestone.completed of its own. Both events are stamped Instant.now(),
        // so writing the cause last puts it AFTER its own effects on the
        // timeline -- a case that reads as created after the milestones inside
        // it completed. See AuditRecorder for the rule.
        audit.record(AuditActions.CASE_CREATED, "onboarding_case", c.getId(),
                "Opened case on workflow " + template.getName() + " v" + versionNoOf(versionId),
                Map.of("customerId", customer.id().toString(), "versionId", versionId.toString()));

        engine.reconcile(c);                       // statuses, progress; stage entry is Task 15
        return toView(c);
    }

    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public CaseView get(UUID caseId) {
        return toView(authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId));
    }

    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public List<CaseView> listForCustomer(UUID customerId) {
        Specification<Case> byCustomer = (root, query, cb) -> cb.equal(root.get("customerId"), customerId);
        return authorizedQuery.findAll(cases, Case.class, PermissionKeys.CASE_VIEW, byCustomer,
                        Pageable.unpaged(Sort.by("startedAt").descending()))
                .map(this::toView)
                .getContent();
    }

    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public RoadmapView roadmap(UUID caseId) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);

        List<Stage> stageRows = readDefinition(stages, Stage.class, c.getVersionId(), "ordinal");
        List<MilestoneDefinition> milestoneDefs = readDefinition(milestoneDefinitions,
                MilestoneDefinition.class, c.getVersionId(), "ordinal");
        List<RequirementDefinition> requirementDefs = readDefinition(requirementDefinitions,
                RequirementDefinition.class, c.getVersionId(), "ordinal");
        List<MilestoneDependency> dependencyRows = readDefinition(milestoneDependencies,
                MilestoneDependency.class, c.getVersionId(), "id");

        List<Milestone> milestoneRows = readCaseChild(milestones, Milestone.class, c.getId());
        List<Requirement> requirementRows = readCaseChild(requirements, Requirement.class, c.getId());

        Map<UUID, Milestone> milestoneByDefinitionId = milestoneRows.stream()
                .collect(toMap(Milestone::getMilestoneDefinitionId, m -> m));
        Map<UUID, List<Requirement>> requirementsByMilestoneId = requirementRows.stream()
                .collect(groupingBy(Requirement::getMilestoneId));
        Map<UUID, List<MilestoneDefinition>> milestoneDefsByStageId = milestoneDefs.stream()
                .collect(groupingBy(MilestoneDefinition::getStageId));
        Map<UUID, RequirementDefinition> requirementDefById = requirementDefs.stream()
                .collect(toMap(RequirementDefinition::getId, d -> d));
        Map<UUID, MilestoneDefinition> milestoneDefById = milestoneDefs.stream()
                .collect(toMap(MilestoneDefinition::getId, d -> d));

        List<StageRoadmapView> stageViews = new ArrayList<>();
        for (Stage stage : stageRows) {
            List<MilestoneRoadmapView> milestoneViews = new ArrayList<>();
            for (MilestoneDefinition def : milestoneDefsByStageId.getOrDefault(stage.getId(), List.of())) {
                Milestone m = milestoneByDefinitionId.get(def.getId());
                if (m == null) continue;
                List<RequirementRoadmapView> requirementViews = requirementsByMilestoneId
                        .getOrDefault(m.getId(), List.of()).stream()
                        .sorted((a, b) -> {
                            var da = requirementDefById.get(a.getRequirementDefinitionId());
                            var db = requirementDefById.get(b.getRequirementDefinitionId());
                            int oa = da == null ? 0 : da.getOrdinal();
                            int ob = db == null ? 0 : db.getOrdinal();
                            return Integer.compare(oa, ob);
                        })
                        .map(r -> {
                            var d = requirementDefById.get(r.getRequirementDefinitionId());
                            return new RequirementRoadmapView(r.getId(), d == null ? null : d.getLabel(),
                                    d == null ? null : d.getKind(), d != null && d.isMandatory(), r.getStatus());
                        })
                        .toList();
                List<String> blockedBy = m.getStatus() != MilestoneStatus.BLOCKED ? List.of()
                        : unmetDependencyNames(def, milestoneByDefinitionId, dependencyRows, milestoneDefById);
                milestoneViews.add(new MilestoneRoadmapView(m.getId(), def.getName(), m.getStatus(),
                        m.getOwnerUserId(), m.getDueDate(), m.getProgressPercent(), blockedBy, requirementViews));
            }
            stageViews.add(new StageRoadmapView(stage.getId(), stage.getName(), stage.getOrdinal(), milestoneViews));
        }
        return new RoadmapView(stageViews);
    }

    /**
     * Read-only mirror of CaseEngine.hasUnmetDependency, computing names rather
     * than a boolean -- CaseEngine discards which dependency was unmet once it has
     * decided BLOCKED, because a transition only needs to know whether to wait,
     * never why. The roadmap is read for a human, so "blocked" without saying by
     * what is colour carrying the whole signal (review finding 10).
     */
    private List<String> unmetDependencyNames(MilestoneDefinition def, Map<UUID, Milestone> milestoneByDefinitionId,
                                              List<MilestoneDependency> dependencyRows,
                                              Map<UUID, MilestoneDefinition> milestoneDefById) {
        List<String> names = new ArrayList<>();
        for (MilestoneDependency d : dependencyRows) {
            if (!def.getId().equals(d.getMilestoneDefinitionId())) continue;
            Milestone dependsOn = milestoneByDefinitionId.get(d.getDependsOnMilestoneDefinitionId());
            boolean settled = dependsOn != null
                    && (dependsOn.getStatus() == MilestoneStatus.DONE || dependsOn.getStatus() == MilestoneStatus.SKIPPED);
            if (settled) continue;
            MilestoneDefinition blockingDef = milestoneDefById.get(d.getDependsOnMilestoneDefinitionId());
            if (blockingDef != null) names.add(blockingDef.getName());
        }
        return names;
    }

    /**
     * Fetched with CASE_EDIT, not CASE_VIEW. Fetching under the read permission and
     * then writing is the privilege escalation CustomerService.update's own comment
     * already names.
     */
    @RequirePermission(PermissionKeys.CASE_EDIT)
    @Transactional
    public CaseView update(UUID caseId, UpdateCaseRequest request) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_EDIT, caseId);

        List<AttributeDefinition> declared = readDefinition(attributeDefinitions,
                AttributeDefinition.class, c.getVersionId(), "ordinal");
        validateAttributes(declared, request.attributes());

        // Every incoming id resolved through AuthorizedQuery before it is written --
        // the write-path shape CLAUDE.md calls "the one that keeps escaping". null is
        // a legal, unresolved value for all three; only a non-null id is checked.
        UUID ownerUserId = request.ownerUserId() == null ? null
                : readOneDefinitionScoped(users, AppUser.class, PermissionKeys.USER_VIEW, request.ownerUserId()).getId();
        // DEPARTMENT_MANAGE/TEAM_MANAGE are ALL-only (PermissionCatalog), so this
        // never needs a Department/Team descriptor -- the same reasoning readDefinition
        // relies on for the workflow-definition entities. Existence and tenancy are
        // still enforced (RLS plus this call), which is the whole point: an invented
        // id 404s here instead of tripping a foreign-key violation at save() the way
        // sub-project 1's still-open CustomerService gap does.
        UUID owningDepartmentId = request.owningDepartmentId() == null ? null
                : readOneDefinitionScoped(departments, Department.class, PermissionKeys.DEPARTMENT_MANAGE,
                        request.owningDepartmentId()).getId();
        UUID owningTeamId = request.owningTeamId() == null ? null
                : readOneDefinitionScoped(teams, Team.class, PermissionKeys.TEAM_MANAGE,
                        request.owningTeamId()).getId();

        boolean ownerChanged = !java.util.Objects.equals(c.getOwnerUserId(), ownerUserId);

        c.setOwnerUserId(ownerUserId);
        c.setOwningDepartmentId(owningDepartmentId);
        c.setOwningTeamId(owningTeamId);
        cases.save(c);

        upsertAttributes(c, declared, request.attributes());

        // Changing the owner re-points DEPARTMENT/TEAM/ASSIGNED scope for the whole
        // case, so the new owner becomes an OWNER participant -- otherwise the case
        // has an owner who cannot open it.
        if (ownerChanged && ownerUserId != null) {
            writeParticipant(c, ownerUserId, RelationshipType.OWNER);
        }

        audit.record(AuditActions.CASE_UPDATED, "onboarding_case", c.getId(),   // cause before effects
                "Updated case", Map.of());

        engine.reconcile(c);
        return toView(c);
    }

    /**
     * Resolves and authorizes the case under CASE_ADVANCE first, exactly as every
     * other mutating method here does, then re-reads it through the engine's row
     * lock before mutating -- the same double-resolve shape update()/addParticipant
     * use. advanceRequested is set on this specific loaded instance only: it is
     * never persisted (see Case.advanceRequested), so it cannot leak into an
     * unrelated reconcile.
     */
    @RequirePermission(PermissionKeys.CASE_ADVANCE)
    @Transactional
    public CaseView advance(UUID caseId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_ADVANCE, caseId);
        Case c = engine.lockAndLoad(caseId);

        UUID stageBefore = c.getCurrentStageId();
        c.setAdvanceRequested(true);
        engine.reconcile(c);

        boolean advanced = !java.util.Objects.equals(stageBefore, c.getCurrentStageId())
                || c.getStatus() == CaseStatus.COMPLETED;
        if (!advanced) {
            throw new StageNotExitableException(caseId);
        }
        return toView(c);
    }

    /**
     * Freezes the case. Does not call engine.reconcile -- holding is a state
     * change, not a recomputation, and CaseEngine.reconcile now early-returns for
     * ON_HOLD anyway (defensive, see its own comment).
     */
    @RequirePermission(PermissionKeys.CASE_HOLD)
    @Transactional
    public CaseView hold(UUID caseId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to hold a case");
        }
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_HOLD, caseId);
        Case c = engine.lockAndLoad(caseId);

        c.setStatus(CaseStatus.ON_HOLD);
        c.setHeldAt(Instant.now(clock));
        cases.save(c);

        audit.record(AuditActions.CASE_HELD, "onboarding_case", c.getId(),
                "Held case: " + reason, Map.of("reason", reason));
        return toView(c);
    }

    /**
     * Shifts every OPEN milestone's due date and the case's target completion by
     * the elapsed business days, and accumulates total_hold_days for sub-project 6
     * to read rather than recompute. Completed/skipped milestones keep their
     * dates -- shifting them would rewrite when the work was actually promised.
     */
    @RequirePermission(PermissionKeys.CASE_HOLD)
    @Transactional
    public CaseView resume(UUID caseId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_HOLD, caseId);
        Case c = engine.lockAndLoad(caseId);
        if (c.getStatus() != CaseStatus.ON_HOLD) throw new CaseNotOnHoldException(caseId);

        int heldBusinessDays = calendar.businessDaysBetween(
                LocalDate.ofInstant(c.getHeldAt(), ZoneOffset.UTC), LocalDate.now(clock));

        for (Milestone m : readCaseChild(milestones, Milestone.class, c.getId())) {
            if (m.getStatus() == MilestoneStatus.DONE || m.getStatus() == MilestoneStatus.SKIPPED) continue;
            if (m.getDueDate() != null) {
                m.setDueDate(calendar.plusBusinessDays(m.getDueDate(), heldBusinessDays));
                milestones.save(m);
            }
        }
        if (c.getTargetCompletionDate() != null) {
            c.setTargetCompletionDate(calendar.plusBusinessDays(c.getTargetCompletionDate(), heldBusinessDays));
        }
        c.setTotalHoldDays(c.getTotalHoldDays() + heldBusinessDays);
        c.setHeldAt(null);
        c.setStatus(CaseStatus.ACTIVE);
        cases.save(c);

        audit.record(AuditActions.CASE_RESUMED, "onboarding_case", caseId,   // cause before effects
                "Resumed after " + heldBusinessDays + " business days on hold",
                Map.of("totalHoldDays", String.valueOf(c.getTotalHoldDays())));

        engine.reconcile(c);
        return toView(c);
    }

    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public List<ParticipantView> participants(UUID caseId) {
        // Confirms the case itself is visible first, so an out-of-scope caseId is a
        // 404 rather than a silently empty list -- the two are not the same claim.
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);

        Specification<CaseParticipant> byCase = (root, query, cb) -> cb.and(
                cb.equal(root.get("caseId"), caseId),
                cb.equal(root.get("status"), ParticipantStatus.ACTIVE));
        List<CaseParticipant> rows = authorizedQuery.findAll(participants, CaseParticipant.class,
                        PermissionKeys.CASE_VIEW, byCase, Pageable.unpaged())
                .getContent();

        return rows.stream().map(p -> new ParticipantView(
                p.getUserId(), fullNameOf(p.getUserId()), p.getRelationship(), p.getStatus())).toList();
    }

    @RequirePermission(PermissionKeys.CASE_EDIT)
    @Transactional
    public void addParticipant(UUID caseId, UUID userId, RelationshipType relationship) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_EDIT, caseId);
        // The user id comes from a request body, so it resolves through
        // AuthorizedQuery under USER_VIEW before the row is written -- 404, never a
        // cross-tenant row, the same shape sub-project 1's open ownership-FK gap
        // shows what skipping this looks like.
        AppUser user = readOneDefinitionScoped(users, AppUser.class, PermissionKeys.USER_VIEW, userId);

        writeParticipant(c, user.getId(), relationship);
        audit.record(AuditActions.CASE_PARTICIPANT_ADDED, "onboarding_case", c.getId(),
                "Added participant to case", Map.of("userId", user.getId().toString(),
                        "relationship", relationship.name()));
    }

    @RequirePermission(PermissionKeys.CASE_EDIT)
    @Transactional
    public void removeParticipant(UUID caseId, UUID userId) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_EDIT, caseId);

        Specification<CaseParticipant> byCaseAndUser = (root, query, cb) -> cb.and(
                cb.equal(root.get("caseId"), c.getId()),
                cb.equal(root.get("userId"), userId),
                cb.equal(root.get("status"), ParticipantStatus.ACTIVE));
        CaseParticipant participant = authorizedQuery.findAll(participants, CaseParticipant.class,
                        PermissionKeys.CASE_EDIT, byCaseAndUser, Pageable.unpaged())
                .getContent().stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("Not found"));

        // Status REMOVED, never a deleted row: CaseDescriptor's/CaseParticipantDescriptor's
        // EXISTS-style subqueries filter on ACTIVE, so a removed participant loses
        // ASSIGNED access on the very next request without erasing the case's history.
        participant.setStatus(ParticipantStatus.REMOVED);
        participants.save(participant);

        audit.record(AuditActions.CASE_PARTICIPANT_REMOVED, "onboarding_case", c.getId(),
                "Removed participant from case", Map.of("userId", userId.toString()));
    }

    /**
     * Every milestone and requirement for every stage, not just the first. The
     * roadmap shows the whole spine from day one with future stages pending, and
     * Task 14's weighted progress needs a denominator that exists before anything is
     * done.
     *
     * Nothing is copied from the definition -- no name, no weight, no duration.
     * Instances join their definitions, because copying drifts and because migration
     * is *meant* to change what a milestone says.
     */
    private void instantiate(Case c, UUID versionId, UUID defaultOwner) {
        var definitions = readDefinition(milestoneDefinitions, MilestoneDefinition.class, versionId, "ordinal");
        var requirementsByMilestone = readDefinition(requirementDefinitions, RequirementDefinition.class,
                        versionId, "ordinal")
                .stream().collect(groupingBy(RequirementDefinition::getMilestoneDefinitionId));

        for (MilestoneDefinition definition : definitions) {
            Milestone m = new Milestone();
            m.setId(Uuid7.generate());
            m.setTenantId(c.getTenantId());
            m.setCaseId(c.getId());
            m.setMilestoneDefinitionId(definition.getId());
            m.setStatus(MilestoneStatus.PENDING);
            m.setOwnerUserId(defaultOwner);
            m.setProgressPercent(0);
            milestones.save(m);

            for (RequirementDefinition rd : requirementsByMilestone.getOrDefault(definition.getId(), List.of())) {
                Requirement r = new Requirement();
                r.setId(Uuid7.generate());
                r.setTenantId(c.getTenantId());
                r.setCaseId(c.getId());            // denormalised so the descriptor is one hop
                r.setMilestoneId(m.getId());
                r.setRequirementDefinitionId(rd.getId());
                r.setStatus(RequirementStatus.OPEN);
                requirements.save(r);
            }
        }
    }

    private void writeParticipant(Case c, UUID userId, RelationshipType relationship) {
        CaseParticipant p = new CaseParticipant();
        p.setId(Uuid7.generate());
        p.setTenantId(c.getTenantId());
        p.setCaseId(c.getId());
        p.setUserId(userId);
        p.setRelationship(relationship);
        p.setStatus(ParticipantStatus.ACTIVE);
        participants.save(p);
    }

    /**
     * Parsing happens here, at the boundary, and never at evaluation time. A branch
     * condition that throws mid-transition wedges a case; one that swallows the
     * error evaluates false and silently skips a stage.
     */
    private void validateAttributes(List<AttributeDefinition> declared, Map<String, String> supplied) {
        List<String> problems = new ArrayList<>();
        Set<String> known = declared.stream().map(AttributeDefinition::getKey).collect(toSet());

        for (String key : supplied.keySet()) {
            if (!known.contains(key)) problems.add("Unknown attribute '" + key + "'");
        }
        for (AttributeDefinition d : declared) {
            String value = supplied.get(d.getKey());
            if (value == null || value.isBlank()) {
                if (d.isRequired()) problems.add("Attribute '" + d.getKey() + "' is required");
                continue;
            }
            if (d.getDataType() == AttributeType.ENUM && d.getAllowedValues() != null
                    && !List.of(d.getAllowedValues()).contains(value)) {
                problems.add("Attribute '" + d.getKey() + "' must be one of "
                        + List.of(d.getAllowedValues()));
            }
            problems.addAll(parseProblems(d, value));
        }
        if (!problems.isEmpty()) throw new AttributeValidationException(problems);
    }

    private List<String> parseProblems(AttributeDefinition d, String value) {
        try {
            switch (d.getDataType()) {
                case NUMBER -> new BigDecimal(value);
                case DATE -> LocalDate.parse(value);
                case BOOLEAN -> {
                    if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
                        return List.of("Attribute '" + d.getKey() + "' must be true or false");
                    }
                }
                default -> { /* STRING, ENUM: no further parse */ }
            }
        } catch (NumberFormatException e) {
            return List.of("Attribute '" + d.getKey() + "' must be a number");
        } catch (java.time.format.DateTimeParseException e) {
            return List.of("Attribute '" + d.getKey() + "' must be a date");
        }
        return List.of();
    }

    /**
     * Upsert, never delete-then-insert: case_attribute_value carries no GRANT DELETE
     * (business values are never hard-deleted, per CLAUDE.md), so update()'s full
     * replace has to update existing rows in place rather than clearing the case's
     * prior answers first. An attribute that had a row and now supplies no value is
     * cleared to all-null columns rather than the row disappearing -- "no answer" is
     * a state this representation can hold without needing DELETE at all.
     */
    private void upsertAttributes(Case c, List<AttributeDefinition> declared, Map<String, String> supplied) {
        Map<UUID, CaseAttributeValue> existing = readCaseChild(attributeValues, CaseAttributeValue.class, c.getId())
                .stream().collect(toMap(CaseAttributeValue::getAttributeDefinitionId, v -> v));

        for (AttributeDefinition d : declared) {
            String value = supplied.get(d.getKey());
            CaseAttributeValue v = existing.get(d.getId());
            if (v == null) {
                if (value == null || value.isBlank()) continue;   // nothing to store, nothing existed
                v = new CaseAttributeValue();
                v.setId(Uuid7.generate());
                v.setTenantId(c.getTenantId());
                v.setCaseId(c.getId());
                v.setAttributeDefinitionId(d.getId());
            }
            v.setValueText(null);
            v.setValueNumber(null);
            v.setValueBoolean(null);
            v.setValueDate(null);
            if (value != null && !value.isBlank()) {
                switch (d.getDataType()) {
                    case NUMBER -> v.setValueNumber(new BigDecimal(value));
                    case DATE -> v.setValueDate(LocalDate.parse(value));
                    case BOOLEAN -> v.setValueBoolean(Boolean.parseBoolean(value));
                    default -> v.setValueText(value);
                }
            }
            attributeValues.save(v);
        }
    }

    private Map<String, String> attributesOf(Case c) {
        List<CaseAttributeValue> rows = readCaseChild(attributeValues, CaseAttributeValue.class, c.getId());
        if (rows.isEmpty()) return Map.of();

        List<AttributeDefinition> declared = readDefinition(attributeDefinitions,
                AttributeDefinition.class, c.getVersionId(), "ordinal");
        Map<UUID, AttributeDefinition> byId = declared.stream()
                .collect(toMap(AttributeDefinition::getId, d -> d));

        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (CaseAttributeValue v : rows) {
            AttributeDefinition d = byId.get(v.getAttributeDefinitionId());
            if (d == null) continue;
            String rendered = switch (d.getDataType()) {
                case NUMBER -> v.getValueNumber() == null ? null : v.getValueNumber().toPlainString();
                case DATE -> v.getValueDate() == null ? null : v.getValueDate().toString();
                case BOOLEAN -> v.getValueBoolean() == null ? null : v.getValueBoolean().toString();
                default -> v.getValueText();
            };
            if (rendered != null) result.put(d.getKey(), rendered);
        }
        return result;
    }

    private String fullNameOf(UUID userId) {
        try {
            return readOneDefinitionScoped(users, AppUser.class, PermissionKeys.USER_VIEW, userId).getFullName();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private int versionNoOf(UUID versionId) {
        return readOneDefinition(versions, WorkflowVersion.class, versionId).getVersionNo();
    }

    /**
     * Reads a workflow-definition entity by version, always under WORKFLOW_VIEW
     * (ALL-only), never a repository finder called directly -- see the class javadoc.
     */
    private <T> List<T> readDefinition(JpaSpecificationExecutor<T> repo, Class<T> type,
                                       UUID versionId, String sortField) {
        Specification<T> byVersion = (root, query, cb) -> cb.equal(root.get("versionId"), versionId);
        return authorizedQuery.findAll(repo, type, PermissionKeys.WORKFLOW_VIEW, byVersion,
                        Pageable.unpaged(Sort.by(sortField)))
                .getContent();
    }

    /** getById under WORKFLOW_VIEW, for the handful of definition entities read by id rather than by version. */
    private <T> T readOneDefinition(JpaSpecificationExecutor<T> repo, Class<T> type, UUID id) {
        return authorizedQuery.getById(repo, type, PermissionKeys.WORKFLOW_VIEW, id);
    }

    /** getById under a caller-supplied permission key, for entities scoped by something other than workflow.view. */
    private <T> T readOneDefinitionScoped(JpaSpecificationExecutor<T> repo, Class<T> type,
                                          String permissionKey, UUID id) {
        return authorizedQuery.getById(repo, type, permissionKey, id);
    }

    /**
     * Reads a journey runtime entity (Milestone, Requirement, CaseAttributeValue)
     * scoped to one already-authorized case, under CASE_VIEW -- resolved by each
     * entity's own viaCase descriptor (MilestoneDescriptor, RequirementDescriptor,
     * CaseAttributeValueDescriptor), never a repository finder called directly.
     */
    private <T> List<T> readCaseChild(JpaSpecificationExecutor<T> repo, Class<T> type, UUID caseId) {
        Specification<T> byCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        return authorizedQuery.findAll(repo, type, PermissionKeys.CASE_VIEW, byCase, Pageable.unpaged())
                .getContent();
    }

    private CaseView toView(Case c) {
        String currentStageName = c.getCurrentStageId() == null ? null
                : readOneDefinition(stages, Stage.class, c.getCurrentStageId()).getName();
        return new CaseView(c.getId(), c.getCustomerId(), c.getTemplateId(), c.getVersionId(),
                versionNoOf(c.getVersionId()), c.getStatus(), c.getCurrentStageId(), currentStageName,
                c.getProgressPercent(), c.getTargetCompletionDate(), c.getHeldAt(), c.getTotalHoldDays(),
                c.getOwnerUserId(), c.getOwningDepartmentId(), c.getOwningTeamId(), attributesOf(c),
                c.getStartedAt(), c.getCompletedAt(), engine.pendingTransition(c));
    }
}
