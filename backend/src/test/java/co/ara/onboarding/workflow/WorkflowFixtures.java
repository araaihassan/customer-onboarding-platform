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
import java.util.UUID;
import java.util.function.UnaryOperator;

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

    // ---- publish-validation scenarios (Task 7) ---------------------------------
    //
    // Every fixture below is a mutation of the same three-stage skeleton (keys
    // s1/s2/s3, each with one milestone m1/m2/m3), which on its own satisfies all
    // five of PublishService's rules. Each fixture breaks exactly one rule, so a
    // test built on it exercises that rule alone; PublishScenario.and lets
    // allProblemsAreReportedTogether combine two into one draft carrying two
    // independent problems. draftWith is the one piece of service-calling glue --
    // it needs a live WorkflowService, so PublishValidationTest supplies its own
    // @Autowired instance rather than this class holding one.

    /**
     * A mutation applied to {@link #publishSkeleton()}. Deliberately not just a
     * {@code WorkflowDefinitionRequest}: {@code and} needs to compose two
     * independent edits (e.g. one stage losing its milestones, another gaining a
     * backward branch) without either overwriting the other's change to a
     * different stage.
     */
    public static final class PublishScenario {
        private final UnaryOperator<WorkflowDefinitionRequest> mutation;

        private PublishScenario(UnaryOperator<WorkflowDefinitionRequest> mutation) {
            this.mutation = mutation;
        }

        public PublishScenario and(PublishScenario other) {
            return new PublishScenario(req -> other.mutation.apply(this.mutation.apply(req)));
        }

        WorkflowDefinitionRequest applyTo(WorkflowDefinitionRequest base) {
            return mutation.apply(base);
        }
    }

    /** Three valid stages, each with one milestone, no conditions or branches: every rule passes. */
    public static PublishScenario threeValidStages() {
        return new PublishScenario(req -> req);
    }

    /** Breaks rule 1: the final stage (s3) gets an entry condition, declared so only rule 1 fires. */
    public static PublishScenario lastStageConditionalOn(String attributeKey, String value) {
        return new PublishScenario(req -> {
            ConditionRequest condition = new ConditionRequest(
                    ConditionSource.ATTRIBUTE, attributeKey, ConditionOperator.EQ, value, null);
            List<StageRequest> updated = req.stages().stream()
                    .map(s -> s.key().equals("s3") ? withEntryCondition(s, condition) : s)
                    .toList();
            List<AttributeRequest> attributes = new ArrayList<>(req.attributes());
            attributes.add(new AttributeRequest(attributeKey, attributeKey, AttributeType.STRING, false, null));
            return new WorkflowDefinitionRequest(updated, attributes, req.lockVersion());
        });
    }

    /** Breaks rule 2: {@code branchFromStage(3).toStage(1)} adds a backward-targeting branch rule. */
    public static BranchTarget branchFromStage(int fromOrdinal) {
        return new BranchTarget(fromOrdinal);
    }

    public static final class BranchTarget {
        private final int fromOrdinal;

        private BranchTarget(int fromOrdinal) {
            this.fromOrdinal = fromOrdinal;
        }

        public PublishScenario toStage(int toOrdinal) {
            String fromKey = "s" + fromOrdinal;
            String toKey = "s" + toOrdinal;
            return new PublishScenario(req -> {
                // branch_rule.source is NOT NULL at the database layer -- unlike a
                // stage's optional entry condition, the condition is a branch rule's
                // whole reason to exist (see Condition's own javadoc) -- so this needs
                // a real condition, not null. CUSTOMER/status is always declared
                // (CustomerFactKeys), so it never trips rule 4 as a side effect.
                ConditionRequest condition = new ConditionRequest(
                        ConditionSource.CUSTOMER, "status", ConditionOperator.EQ, "ACTIVE", null);
                BranchRuleRequest rule = new BranchRuleRequest(condition, toKey);
                List<StageRequest> updated = req.stages().stream()
                        .map(s -> s.key().equals(fromKey) ? addBranchRule(s, rule) : s)
                        .toList();
                return new WorkflowDefinitionRequest(updated, req.attributes(), req.lockVersion());
            });
        }
    }

    /** Breaks rule 3: stage s1's first milestone depends on the one declared right after it. */
    public static PublishScenario firstMilestoneDependingOnTheSecond() {
        return new PublishScenario(req -> {
            StageRequest reworked = stage("s1", "Stage One", List.of(
                    milestone("m1", "Milestone One", 1, List.of("m1b"), List.of(manual("Do it"))),
                    milestone("m1b", "Milestone One-B", 1, List.of(), List.of(manual("Do it")))));
            List<StageRequest> updated = req.stages().stream()
                    .map(s -> s.key().equals("s1") ? reworked : s)
                    .toList();
            return new WorkflowDefinitionRequest(updated, req.attributes(), req.lockVersion());
        });
    }

    /** Breaks rule 4: stage s1 (not the final stage, so rule 1 stays clean) gets an undeclared condition. */
    public static PublishScenario conditionOnAttribute(String undeclaredKey) {
        return new PublishScenario(req -> {
            ConditionRequest condition = new ConditionRequest(
                    ConditionSource.ATTRIBUTE, undeclaredKey, ConditionOperator.EQ, "x", null);
            List<StageRequest> updated = req.stages().stream()
                    .map(s -> s.key().equals("s1") ? withEntryCondition(s, condition) : s)
                    .toList();
            return new WorkflowDefinitionRequest(updated, req.attributes(), req.lockVersion());
        });
    }

    /** Breaks rule 5: stage s1 loses its milestone entirely. */
    public static PublishScenario anEmptyStage() {
        return new PublishScenario(req -> {
            List<StageRequest> updated = req.stages().stream()
                    .map(s -> s.key().equals("s1") ? withMilestones(s, List.of()) : s)
                    .toList();
            return new WorkflowDefinitionRequest(updated, req.attributes(), req.lockVersion());
        });
    }

    /**
     * Creates a template and a draft, applies {@code scenario} to {@link
     * #publishSkeleton()} and saves it, returning the draft's id -- the one step in
     * this DSL that needs a live {@link WorkflowService} rather than just building
     * request records.
     */
    public static UUID draftWith(WorkflowService workflows, PublishScenario scenario) {
        UUID templateId = workflows.createTemplate("Publish fixture", "").id();
        UUID draftId = workflows.createDraft(templateId);
        workflows.replaceDraft(draftId, scenario.applyTo(publishSkeleton()));
        return draftId;
    }

    private static WorkflowDefinitionRequest publishSkeleton() {
        return new WorkflowDefinitionRequest(
                List.of(
                        stage("s1", "Stage One", List.of(
                                milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it"))))),
                        stage("s2", "Stage Two", List.of(
                                milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it"))))),
                        stage("s3", "Stage Three", List.of(
                                milestone("m3", "Milestone Three", 1, List.of(), List.of(manual("Do it")))))),
                List.of(), 0L);
    }

    private static StageRequest withEntryCondition(StageRequest s, ConditionRequest condition) {
        return new StageRequest(s.key(), s.name(), s.responsibleDepartmentId(), s.requiresApproval(),
                s.autoAdvance(), s.portalVisible(), s.slaDays(), s.writeScope(),
                s.notificationTemplateKey(), condition, s.fallbackNextStageKey(),
                s.milestones(), s.branchRules());
    }

    private static StageRequest withMilestones(StageRequest s, List<MilestoneRequest> milestones) {
        return new StageRequest(s.key(), s.name(), s.responsibleDepartmentId(), s.requiresApproval(),
                s.autoAdvance(), s.portalVisible(), s.slaDays(), s.writeScope(),
                s.notificationTemplateKey(), s.entryCondition(), s.fallbackNextStageKey(),
                milestones, s.branchRules());
    }
}
