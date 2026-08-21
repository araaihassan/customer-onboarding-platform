package co.ara.onboarding.workflow;

import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.BranchRuleRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.ConditionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.MilestoneRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.RequirementRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.StageRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionView.AttributeView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.BranchRuleView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.ConditionView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.MilestoneView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.RequirementView;
import co.ara.onboarding.workflow.WorkflowDefinitionView.StageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared authoring builders, used verbatim by Tasks 7, 13 and 19 as well as
 * {@link WorkflowAuthoringTest} -- kept here rather than duplicated per test class so
 * the graph shapes those later tasks assume stay in exactly one place.
 */
public final class WorkflowFixtures {

    private WorkflowFixtures() {}

    /** A stage with sensible authoring defaults and no branch rules yet. */
    public static StageRequest stage(String key, String name, List<MilestoneRequest> milestones) {
        return new StageRequest(key, name, null, false, true, true, null, WriteScope.ANY, null,
                null, null, milestones, List.of());
    }

    public static MilestoneRequest milestone(String key, String name, int estimatedDurationDays,
                                              List<String> dependsOnMilestoneKeys,
                                              List<RequirementRequest> requirements) {
        return new MilestoneRequest(key, name, null, estimatedDurationDays,
                dependsOnMilestoneKeys, requirements);
    }

    public static RequirementRequest manual(String label) {
        return new RequirementRequest(RequirementKind.MANUAL, label, 1, true, null, null);
    }

    public static RequirementRequest document(String label, String category) {
        return new RequirementRequest(RequirementKind.DOCUMENT, label, 1, true, category, null);
    }

    /**
     * Appends a branch rule to the named stage: "if the ATTRIBUTE named attributeKey
     * equals value, go to targetStageKey". Rebuilds the request rather than mutating
     * it -- every record here is immutable by design.
     */
    public static WorkflowDefinitionRequest withBranch(WorkflowDefinitionRequest request, String stageKey,
                                                        String attributeKey, String value,
                                                        String targetStageKey) {
        ConditionRequest condition = new ConditionRequest(
                ConditionSource.ATTRIBUTE, attributeKey, ConditionOperator.EQ, value, null);
        BranchRuleRequest rule = new BranchRuleRequest(condition, targetStageKey);

        List<StageRequest> updated = request.stages().stream()
                .map(s -> s.key().equals(stageKey) ? addBranchRule(s, rule) : s)
                .toList();
        return new WorkflowDefinitionRequest(updated, request.attributes(), request.lockVersion());
    }

    private static StageRequest addBranchRule(StageRequest s, BranchRuleRequest rule) {
        List<BranchRuleRequest> rules = new ArrayList<>(s.branchRules());
        rules.add(rule);
        return new StageRequest(s.key(), s.name(), s.responsibleDepartmentId(), s.requiresApproval(),
                s.autoAdvance(), s.portalVisible(), s.slaDays(), s.writeScope(),
                s.notificationTemplateKey(), s.entryCondition(), s.fallbackNextStageKey(),
                s.milestones(), rules);
    }

    /** Two bare stages, no milestones, ready for a first replaceDraft on a fresh (lockVersion 0) draft. */
    public static WorkflowDefinitionRequest twoStages() {
        return new WorkflowDefinitionRequest(
                List.of(stage("a", "Stage A", List.of()), stage("b", "Stage B", List.of())),
                List.of(), 0L);
    }

    /**
     * The current view's first stage only, carrying the view's own lockVersion
     * forward -- so the caller can replaceDraft again without a stale-lock rejection.
     */
    public static WorkflowDefinitionRequest oneStage(WorkflowDefinitionView view) {
        return new WorkflowDefinitionRequest(
                List.of(toStageRequest(view.stages().get(0))),
                view.attributes().stream().map(WorkflowFixtures::toAttributeRequest).toList(),
                view.lockVersion());
    }

    private static StageRequest toStageRequest(StageView s) {
        return new StageRequest(s.key(), s.name(), s.responsibleDepartmentId(), s.requiresApproval(),
                s.autoAdvance(), s.portalVisible(), s.slaDays(), s.writeScope(),
                s.notificationTemplateKey(), toConditionRequest(s.entryCondition()),
                s.fallbackNextStageKey(),
                s.milestones().stream().map(WorkflowFixtures::toMilestoneRequest).toList(),
                s.branchRules().stream().map(WorkflowFixtures::toBranchRuleRequest).toList());
    }

    private static MilestoneRequest toMilestoneRequest(MilestoneView m) {
        return new MilestoneRequest(m.key(), m.name(), m.description(), m.estimatedDurationDays(),
                m.dependsOnMilestoneKeys(),
                m.requirements().stream().map(WorkflowFixtures::toRequirementRequest).toList());
    }

    private static RequirementRequest toRequirementRequest(RequirementView r) {
        return new RequirementRequest(r.kind(), r.label(), r.weight(), r.mandatory(),
                r.documentCategory(), r.approverRelationship());
    }

    private static BranchRuleRequest toBranchRuleRequest(BranchRuleView b) {
        return new BranchRuleRequest(toConditionRequest(b.condition()), b.targetStageKey());
    }

    private static AttributeRequest toAttributeRequest(AttributeView a) {
        return new AttributeRequest(a.key(), a.label(), a.dataType(), a.required(), a.allowedValues());
    }

    private static ConditionRequest toConditionRequest(ConditionView c) {
        if (c == null) return null;
        return new ConditionRequest(c.source(), c.key(), c.operator(), c.value(), c.values());
    }
}
