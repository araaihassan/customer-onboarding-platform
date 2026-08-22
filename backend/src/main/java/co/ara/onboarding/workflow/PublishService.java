package co.ara.onboarding.workflow;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

/**
 * Publish-time validation and the freeze it guards (spec 6.5). A draft is allowed to
 * be temporarily incoherent while an admin edits it -- {@link WorkflowService}'s own
 * {@code validateRequestShape} checks only what is structurally impossible -- but
 * publish is the gate that decides whether Task 15's case engine could ever actually
 * run the graph.
 */
@Service
public class PublishService {

    private final WorkflowTemplateRepository templates;
    private final WorkflowVersionRepository versions;
    private final StageRepository stages;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final MilestoneDependencyRepository dependencies;
    private final AttributeDefinitionRepository attributeDefinitions;
    private final BranchRuleRepository branchRules;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final WorkflowService workflows;

    public PublishService(WorkflowTemplateRepository templates,
                          WorkflowVersionRepository versions,
                          StageRepository stages,
                          MilestoneDefinitionRepository milestoneDefinitions,
                          MilestoneDependencyRepository dependencies,
                          AttributeDefinitionRepository attributeDefinitions,
                          BranchRuleRepository branchRules,
                          AuthorizedQuery authorizedQuery,
                          AuthContextProvider contextProvider,
                          AuditRecorder audit,
                          WorkflowService workflows) {
        this.templates = templates;
        this.versions = versions;
        this.stages = stages;
        this.milestoneDefinitions = milestoneDefinitions;
        this.dependencies = dependencies;
        this.attributeDefinitions = attributeDefinitions;
        this.branchRules = branchRules;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.workflows = workflows;
    }

    @RequirePermission(PermissionKeys.WORKFLOW_MANAGE)
    @Transactional
    public WorkflowDefinitionView publish(UUID versionId) {
        WorkflowVersion version = authorizedQuery.getById(
                versions, WorkflowVersion.class, PermissionKeys.WORKFLOW_MANAGE, versionId);
        if (version.getStatus() != VersionStatus.DRAFT) throw new VersionNotEditableException(versionId);

        List<String> problems = validate(versionId);
        if (!problems.isEmpty()) throw new PublishValidationException(problems);

        version.setStatus(VersionStatus.PUBLISHED);          // the last legal UPDATE to this row
        version.setPublishedAt(Instant.now());
        version.setPublishedBy(contextProvider.principal().userId());

        WorkflowTemplate template = authorizedQuery.getById(
                templates, WorkflowTemplate.class, PermissionKeys.WORKFLOW_MANAGE, version.getTemplateId());
        // "No longer offered to new cases" is this pointer moving -- never a status change on
        // a frozen row, which the trigger would refuse anyway.
        template.setCurrentVersionId(versionId);

        audit.record(AuditActions.WORKFLOW_PUBLISHED, "workflow_version", versionId,
                "Published v" + version.getVersionNo() + " of " + template.getName(), Map.of());
        return definitionOf(versionId);
    }

    /**
     * Built under WORKFLOW_MANAGE -- the permission publish's own gate already
     * checked -- via WorkflowService.getDefinitionAs, rather than re-authorizing
     * under WORKFLOW_VIEW and risking the same manage-without-view gap
     * WorkflowService.replaceDraft's own return statement avoids.
     */
    private WorkflowDefinitionView definitionOf(UUID versionId) {
        return workflows.getDefinitionAs(versionId, PermissionKeys.WORKFLOW_MANAGE);
    }

    /**
     * Collects every problem rather than throwing on the first. An admin fixing a
     * nine-stage workflow one error per round trip is a bad experience; a validator that
     * only ever reports one problem is also indistinguishable from one that implements
     * only one rule.
     *
     * The five lists below are read via {@link #readByVersion}, not the repositories'
     * own {@code findByVersionId*} finders directly: workflow.manage is ALL-only today,
     * but AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly binds to
     * every *Service in this package regardless, and a repository finder called
     * directly is exactly the silent bypass shape that guard exists to catch.
     */
    private List<String> validate(UUID versionId) {
        List<Stage> stages = readByVersion(this.stages, Stage.class, versionId, "ordinal");
        List<MilestoneDefinition> milestones =
                readByVersion(milestoneDefinitions, MilestoneDefinition.class, versionId, "ordinal");
        List<MilestoneDependency> deps = readByVersion(dependencies, MilestoneDependency.class, versionId, "id");
        List<BranchRule> rules = readByVersion(branchRules, BranchRule.class, versionId, "ordinal");
        Set<String> declared = readByVersion(attributeDefinitions, AttributeDefinition.class, versionId, "ordinal")
                .stream().map(AttributeDefinition::getKey).collect(toSet());

        List<String> problems = new ArrayList<>();
        if (stages.isEmpty()) problems.add("A workflow needs at least one stage");

        Map<UUID, Integer> stageOrdinal = stages.stream()
                .collect(toMap(Stage::getId, Stage::getOrdinal));
        Map<UUID, MilestoneDefinition> milestoneById = milestones.stream()
                .collect(toMap(MilestoneDefinition::getId, m -> m));

        // Rule 1: the final stage must be unconditionally enterable, or the skip loop in
        // CaseEngine can walk past the end of the workflow with nowhere to land.
        if (!stages.isEmpty()) {
            Stage last = stages.get(stages.size() - 1);
            if (last.getEntryCondition() != null && last.getEntryCondition().getSource() != null) {
                problems.add("The final stage (" + last.getName() + ") must have no entry condition");
            }
        }

        // Rule 5, and Rule 4 for entry conditions.
        for (Stage stage : stages) {
            if (milestones.stream().noneMatch(m -> m.getStageId().equals(stage.getId()))) {
                problems.add("Stage " + stage.getName() + " has no milestone");
            }
            problems.addAll(conditionProblems(stage.getEntryCondition(), declared,
                    "entry condition of " + stage.getName()));
        }

        // Rule 2, and Rule 4 for branch conditions.
        for (BranchRule rule : rules) {
            int from = stageOrdinal.getOrDefault(rule.getStageId(), -1);
            int to = stageOrdinal.getOrDefault(rule.getTargetStageId(), -1);
            if (to <= from) {
                problems.add("Branch rule on stage " + from + " must target a forward stage, not " + to);
            }
            problems.addAll(conditionProblems(rule.getCondition(), declared,
                    "branch rule on stage " + from));
        }

        // Rule 3: dependencies point strictly earlier in plan order, which is
        // (stage ordinal, milestone ordinal). A forward dependency describes a plan the
        // engine will never follow, because the schedule treats intra-stage milestones as
        // sequential by ordinal.
        for (MilestoneDependency dep : deps) {
            var self = milestoneById.get(dep.getMilestoneDefinitionId());
            var other = milestoneById.get(dep.getDependsOnMilestoneDefinitionId());
            if (self == null || other == null) continue;
            long selfKey  = planOrder(stageOrdinal, self);
            long otherKey = planOrder(stageOrdinal, other);
            if (otherKey >= selfKey) {
                problems.add("Milestone " + self.getName() + " must depend on an earlier milestone, not "
                        + other.getName());
            }
        }
        return problems;
    }

    private List<String> conditionProblems(Condition condition, Set<String> declared, String where) {
        if (condition == null || condition.getSource() == null) return List.of();
        if (condition.getSource() == ConditionSource.ATTRIBUTE && !declared.contains(condition.getKey())) {
            return List.of("The " + where + " names attribute '" + condition.getKey()
                    + "', which this workflow does not declare");
        }
        if (condition.getSource() == ConditionSource.CUSTOMER
                && !CustomerFactKeys.ALL.contains(condition.getKey())) {
            return List.of("The " + where + " names customer field '" + condition.getKey()
                    + "', which does not exist");
        }
        return List.of();
    }

    private long planOrder(Map<UUID, Integer> stageOrdinal, MilestoneDefinition milestone) {
        return stageOrdinal.get(milestone.getStageId()) * 1000L + milestone.getOrdinal();
    }

    /** Reads a version's children through AuthorizedQuery -- see the note on {@link #validate}. */
    private <T> List<T> readByVersion(JpaSpecificationExecutor<T> repo, Class<T> type,
                                      UUID versionId, String sortField) {
        Specification<T> byVersion = (root, query, cb) -> cb.equal(root.get("versionId"), versionId);
        return authorizedQuery.findAll(repo, type, PermissionKeys.WORKFLOW_MANAGE, byVersion,
                        Pageable.unpaged(Sort.by(sortField)))
                .getContent();
    }
}
