package co.ara.onboarding.workflow;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static co.ara.onboarding.workflow.WorkflowDefinitionRequest.MilestoneRequest;
import static co.ara.onboarding.workflow.WorkflowDefinitionRequest.StageRequest;
import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project 3A, Task 21: {@link PlanShapeService#render} -- the first thing in the
 * codebase to read either {@code stage.portal_visible} (existing since sub-project 2's
 * {@code V12}) or {@code milestone_definition.portal_visible} (Task 18). Both flags were
 * authored and inert until this task.
 */
class PlanShapeRenderingTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publisher;
    @Autowired PlanShapeService planShapeService;

    private UUID tenant;
    private UUID admin;

    @BeforeEach
    void seedTenant() {
        tenant = fixture.createTenant("plan-shape-rendering-" + Uuid7.generate());
        admin = fixture.createAdminUser(tenant, "admin+" + Uuid7.generate() + "@plan-shape-rendering.example").getId();
    }

    @Test
    void theRenderingOmitsInternalOnlyStagesAndMilestones() {
        UUID versionId = publishVersionWith(
                stage("s1", /* portalVisible */ true,  milestone("m1", true), milestone("m2", /* internal */ false)),
                stage("s2", /* portalVisible */ false, milestone("m3", true)));

        PlanShapeView rendered = render(versionId);

        assertThat(rendered.stages()).extracting(PlanShapeStageView::label).containsExactly("s1");
        assertThat(rendered.stages().get(0).milestones()).extracting(PlanShapeMilestoneView::label)
                .containsExactly("m1");
    }

    @Test
    void anInternalOnlyStageHidesItsMilestonesEvenWhenTheyAreVisibleThemselves() {
        // m3 is portal_visible but its stage is not. A milestone the customer cannot
        // reach must not appear in the artifact they are asked to approve.
        UUID versionId = publishVersionWith(
                stage("s1", true, milestone("m1", true)),
                stage("s2", false, milestone("m3", true)));

        assertThat(renderedLabels(versionId)).doesNotContain("m3");
    }

    @Test
    void idIsTheStableIdentityEvenWhenTwoMilestonesShareALabel() {
        // Two milestones with distinct authoring keys but the same display name --
        // nothing in WorkflowService's authoring validation forbids this (only the
        // authoring KEY has to be unique, never the name). `label` -- sourced from the
        // entity's own `name` -- carries no uniqueness guarantee at all, which is
        // exactly why it is called `label` and not `key`: a future portal client keying
        // a list or a lookup off this artifact must use `id`, never `label`.
        UUID versionId = publishVersionWith(
                stage("s1", true,
                        milestoneNamed("m1a", "Review", true),
                        milestoneNamed("m1b", "Review", true)));

        List<PlanShapeMilestoneView> milestones = render(versionId).stages().get(0).milestones();
        assertThat(milestones).extracting(PlanShapeMilestoneView::label).containsExactly("Review", "Review");
        assertThat(milestones).extracting(PlanShapeMilestoneView::id).doesNotHaveDuplicates();
    }

    @Test
    void theRenderingCarriesEstimatedDurationsButNoDatesOrOwners() {
        // Gate 1 approves a SHAPE and a duration. Dates and owners are runtime columns on
        // the case and belong to gate 2 -- a rendering that showed them would be
        // promising the customer a schedule at the wrong gate.
        Set<String> fields = componentNames(PlanShapeMilestoneView.class);
        assertThat(fields).contains("estimatedDurationDays");
        assertThat(fields).doesNotContain("dueDate", "ownerUserId");
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Builds and publishes a version through the same {@link WorkflowService} authoring
     * path a real client would use -- not hand-built entities -- so this proves render()
     * against the actual persisted graph, portal_visible included.
     */
    private UUID publishVersionWith(StageRequest... stages) {
        AtomicReference<UUID> draftId = new AtomicReference<>();
        fixture.runAsUser(tenant, admin, () -> {
            UUID templateId = workflows.createTemplate("Plan Shape Rendering " + Uuid7.generate(), "").id();
            UUID id = workflows.createDraft(templateId);
            workflows.replaceDraft(id, new WorkflowDefinitionRequest(List.of(stages), List.of(), 0L));
            publisher.publish(id);
            draftId.set(id);
        });
        return draftId.get();
    }

    private PlanShapeView render(UUID versionId) {
        AtomicReference<PlanShapeView> rendered = new AtomicReference<>();
        fixture.runAsUser(tenant, admin, () -> rendered.set(planShapeService.render(versionId)));
        return rendered.get();
    }

    /**
     * Authoring key/name are the same string here (e.g. "s1") -- {@link Stage} and
     * {@link MilestoneDefinition} persist no separate authoring key, so
     * PlanShapeStageView/PlanShapeMilestoneView label off the entity's own name (see
     * their own javadoc). Most tests in this class don't care about the key/name
     * distinction, so this convenience helper collapses them; {@link
     * #milestoneNamed(String, String, boolean)} is the one that doesn't, for the test
     * that needs two milestones sharing a label under different authoring keys.
     */
    private StageRequest stage(String key, boolean portalVisible, MilestoneRequest... milestones) {
        StageRequest base = WorkflowFixtures.stage(key, key, List.of(milestones));
        return new StageRequest(base.key(), base.name(), base.responsibleDepartmentId(), base.requiresApproval(),
                base.autoAdvance(), portalVisible, base.slaDays(), base.writeScope(),
                base.notificationTemplateKey(), base.entryCondition(), base.fallbackNextStageKey(),
                base.milestones(), base.branchRules());
    }

    private MilestoneRequest milestone(String key, boolean portalVisible) {
        return WorkflowFixtures.milestone(key, key, 1, List.of(), List.of(manual("Do it")), portalVisible);
    }

    private MilestoneRequest milestoneNamed(String key, String name, boolean portalVisible) {
        return WorkflowFixtures.milestone(key, name, 1, List.of(), List.of(manual("Do it")), portalVisible);
    }

    private List<String> renderedLabels(UUID versionId) {
        return render(versionId).stages().stream()
                .flatMap(s -> s.milestones().stream())
                .map(PlanShapeMilestoneView::label)
                .toList();
    }

    private static Set<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toSet());
    }
}
