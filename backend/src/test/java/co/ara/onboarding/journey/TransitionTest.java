package co.ara.onboarding.journey;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AttributeType;
import co.ara.onboarding.workflow.ConditionOperator;
import co.ara.onboarding.workflow.ConditionSource;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowFixtures;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises CaseEngine.advanceIfExitable through CaseService/CaseEngine directly --
 * CaseEngine is package-private, so this test lives alongside it, the same
 * convention ReconcileTest already established. RequirementService (Task 16)
 * doesn't exist yet, so "satisfying" a requirement here is a direct repository
 * write followed by engine.reconcile, exactly ReconcileTest's own pattern.
 */
class TransitionTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired CaseEngine engine;
    @Autowired CaseRepository caseRepository;
    @Autowired RequirementRepository requirements;
    @Autowired ApprovalRepository approvals;
    @Autowired BusinessCalendar calendar;

    @Test
    void completingEveryMilestoneInAStageEntersTheNextOne() {
        UUID tenant = fixture.createTenant("trans-basic");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Stage One", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s2", "Stage Two", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));

            UUID s1Id = stageId(view.id(), 0);
            UUID s2Id = stageId(view.id(), 1);
            assertThat(view.currentStageId()).isEqualTo(s1Id);

            satisfyEveryRequirementOfCurrentStage(view.id());

            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s2Id);
        });
    }

    @Test
    void anOptionalRequirementDoesNotHoldAStageOpen() {
        UUID tenant = fixture.createTenant("trans-optional");
        fixture.runAs(tenant, () -> {
            var optional = new WorkflowDefinitionRequest.RequirementRequest(
                    RequirementKind.MANUAL, "Optional", 1, false, null, null);
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Stage One", List.of(
                            milestone("m1", "M1", 1, List.of(), List.of(manual("Mandatory"), optional)))),
                    stage("s2", "Stage Two", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));

            var mandatoryReq = cases.roadmap(view.id()).stages().get(0).milestones().get(0).requirements()
                    .stream().filter(RequirementRoadmapView::mandatory).findFirst().orElseThrow();
            satisfyRequirement(mandatoryReq.id());

            UUID s2Id = stageId(view.id(), 1);
            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s2Id);
        });
    }

    /** First match wins, in ordinal order — not "the most specific rule". */
    @Test
    void theFirstMatchingBranchRuleWins() {
        UUID tenant = fixture.createTenant("trans-branch-priority");
        fixture.runAs(tenant, () -> {
            var attrs = List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                    List.of("SMB", "ENTERPRISE")));
            var base = new WorkflowDefinitionRequest(List.of(
                    stage("s1", "S1", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s2", "S2", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s3", "S3", List.of(milestone("m3", "M3", 1, List.of(), List.of(manual("Do it")))))
            ), attrs, 0L);
            // Both rules match segment=SMB; the first one added (lower ordinal) must win.
            var withRule1 = WorkflowFixtures.withBranch(base, "s1", "segment", "SMB", "s2");
            var withBothRules = WorkflowFixtures.withBranch(withRule1, "s1", "segment", "SMB", "s3");
            UUID versionId = journey.publish(withBothRules);

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), Map.of("segment", "SMB")));

            satisfyEveryRequirementOfCurrentStage(view.id());

            UUID s2Id = stageId(view.id(), 1);
            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s2Id);
        });
    }

    /**
     * s1 declares no branch rules at all -- the "no matching rule" case in its most
     * literal form -- but an explicit fallback to s3. Landing on s3 rather than s2
     * (the ordinal-plus-one default) proves the fallback is honoured ahead of it.
     */
    @Test
    void noMatchingRuleFallsBackToTheDeclaredFallbackThenToTheNextOrdinal() {
        UUID tenant = fixture.createTenant("trans-fallback");
        fixture.runAs(tenant, () -> {
            var s1 = new WorkflowDefinitionRequest.StageRequest("s1", "S1", null, false, true, true, null,
                    WriteScope.ANY, null, null, "s3",
                    List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it")))), List.of());
            var request = new WorkflowDefinitionRequest(List.of(
                    s1,
                    stage("s2", "S2", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s3", "S3", List.of(milestone("m3", "M3", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L);
            UUID versionId = journey.publish(request);

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));

            satisfyEveryRequirementOfCurrentStage(view.id());

            UUID s2Id = stageId(view.id(), 1);
            UUID s3Id = stageId(view.id(), 2);
            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s3Id);

            // s2, jumped over by the fallback, is skipped rather than left permanently PENDING.
            var s2Milestone = cases.roadmap(view.id()).stages().stream()
                    .filter(s -> s.id().equals(s2Id)).findFirst().orElseThrow().milestones().get(0);
            assertThat(s2Milestone.status()).isEqualTo(MilestoneStatus.SKIPPED);
        });
    }

    /** Q7's own example: SMB skips Legal Review. */
    @Test
    void aBranchSkipsTheStagesItJumpsOver() {
        UUID tenant = fixture.createTenant("trans-branch-skip");
        fixture.runAs(tenant, () -> {
            var attrs = List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                    List.of("SMB", "ENTERPRISE")));
            var base = new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Onboarding", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s2", "Legal Review", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it"))))),
                    stage("s3", "Go Live", List.of(milestone("m3", "M3", 1, List.of(), List.of(manual("Do it")))))
            ), attrs, 0L);
            var withBranch = WorkflowFixtures.withBranch(base, "s1", "segment", "SMB", "s3");
            UUID versionId = journey.publish(withBranch);

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), Map.of("segment", "SMB")));

            satisfyEveryRequirementOfCurrentStage(view.id());

            UUID s2Id = stageId(view.id(), 1);
            UUID s3Id = stageId(view.id(), 2);
            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s3Id);

            var s2Milestone = cases.roadmap(view.id()).stages().stream()
                    .filter(s -> s.id().equals(s2Id)).findFirst().orElseThrow().milestones().get(0);
            assertThat(s2Milestone.status()).isEqualTo(MilestoneStatus.SKIPPED);
        });
    }

    @Test
    void aStageWhoseEntryConditionIsFalseIsSkippedAndTheLoopContinues() {
        UUID tenant = fixture.createTenant("trans-entry-cond");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(threeStageOnboardingWithConditionalLegalReview());

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), Map.of("segment", "SMB")));

            satisfyEveryRequirementOfCurrentStage(view.id());

            UUID s2Id = stageId(view.id(), 1);
            UUID s3Id = stageId(view.id(), 2);
            assertThat(cases.get(view.id()).currentStageId()).isEqualTo(s3Id);

            var s2Milestone = cases.roadmap(view.id()).stages().stream()
                    .filter(s -> s.id().equals(s2Id)).findFirst().orElseThrow().milestones().get(0);
            assertThat(s2Milestone.status()).isEqualTo(MilestoneStatus.SKIPPED);
        });
    }

    /**
     * The terminal rule: current_stage_id lands on a real stage, never on a skipped one
     * and never null, and the case completes.
     */
    @Test
    void exitingTheFinalStageCompletesTheCase() {
        UUID tenant = fixture.createTenant("trans-complete");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Only Stage", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));
            UUID s1Id = stageId(view.id(), 0);

            satisfyEveryRequirementOfCurrentStage(view.id());

            var reloaded = cases.get(view.id());
            assertThat(reloaded.status()).isEqualTo(CaseStatus.COMPLETED);
            assertThat(reloaded.completedAt()).isNotNull();
            assertThat(reloaded.currentStageId()).isEqualTo(s1Id);
            assertThat(reloaded.progressPercent()).isEqualTo(100);
        });
    }

    @Test
    void aCaseThatSkipsItsWayToTheEndStillCompletesOnARealStage() {
        UUID tenant = fixture.createTenant("trans-skip-to-end");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(threeStageOnboardingWithConditionalLegalReview());

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), Map.of("segment", "SMB")));

            satisfyEveryRequirementOfCurrentStage(view.id());   // s1 done -> s2 skipped -> lands on s3
            satisfyEveryRequirementOfCurrentStage(view.id());   // s3 done -> terminal

            var reloaded = cases.get(view.id());
            UUID s3Id = stageId(view.id(), 2);
            assertThat(reloaded.status()).isEqualTo(CaseStatus.COMPLETED);
            assertThat(reloaded.currentStageId()).isEqualTo(s3Id);
            assertThat(reloaded.progressPercent()).isEqualTo(100);
        });
    }

    /** auto_advance false: the engine computes the transition but does not take it. */
    @Test
    void aManualStageWaitsForSomeoneWithCaseAdvance() {
        UUID tenant = fixture.createTenant("trans-manual");
        fixture.runAs(tenant, () -> {
            var manualStage = new WorkflowDefinitionRequest.StageRequest("s1", "Stage One", null, false, false,
                    true, null, WriteScope.ANY, null, null, null,
                    List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it")))), List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    manualStage,
                    stage("s2", "Stage Two", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));
            UUID s1Id = stageId(view.id(), 0);
            UUID s2Id = stageId(view.id(), 1);

            satisfyEveryRequirementOfCurrentStage(view.id());   // exitable now, but auto_advance=false

            var afterSatisfy = cases.get(view.id());
            assertThat(afterSatisfy.currentStageId()).isEqualTo(s1Id);
            assertThat(afterSatisfy.availableTransition()).isNotNull();
            assertThat(afterSatisfy.availableTransition().nextStageId()).isEqualTo(s2Id);
            assertThat(afterSatisfy.availableTransition().approvalPending()).isFalse();

            var advanced = cases.advance(view.id());
            assertThat(advanced.currentStageId()).isEqualTo(s2Id);
        });
    }

    @Test
    void advancingAStageThatIsNotExitableIsRefused() {
        UUID tenant = fixture.createTenant("trans-not-exitable");
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Stage One", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            caseId.set(cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of())).id());
        });

        // Never assert inside the runAs lambda -- see CaseCreationTest's own note on why.
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.advance(caseId.get())))
                .isInstanceOf(StageNotExitableException.class);
    }

    /** Nothing moves while an approval is pending, however complete the stage is. */
    @Test
    void aPendingApprovalHoldsTheStage() {
        UUID tenant = fixture.createTenant("trans-approval");
        fixture.runAs(tenant, () -> {
            var approvalStage = new WorkflowDefinitionRequest.StageRequest("s1", "Stage One", null, true, true,
                    true, null, WriteScope.ANY, null, null, null,
                    List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it")))), List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    approvalStage,
                    stage("s2", "Stage Two", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));

            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));
            UUID s1Id = stageId(view.id(), 0);

            satisfyEveryRequirementOfCurrentStage(view.id());   // stage complete, but requires approval

            var reloaded = cases.get(view.id());
            assertThat(reloaded.currentStageId()).isEqualTo(s1Id);
            assertThat(reloaded.availableTransition()).isNotNull();
            assertThat(reloaded.availableTransition().approvalPending()).isTrue();

            // A second, unrelated reconcile must not create a second PENDING approval row.
            reconcileAgain(view.id());
            assertThat(approvals.findByCaseIdAndStatus(view.id(), ApprovalStatus.PENDING)).hasSize(1);
        });
    }

    /** Due dates are set on stage entry, in business days, sequential within the stage. */
    @Test
    void enteringAStageSchedulesItsMilestones() {
        UUID tenant = fixture.createTenant("trans-schedule");
        fixture.runAs(tenant, () -> {
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(
                    stage("s1", "Stage One", List.of(
                            milestone("m1", "M1", 2, List.of(), List.of(manual("Do it"))),
                            milestone("m2", "M2", 3, List.of(), List.of(manual("Do it")))))
            ), List.of(), 0L));
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            var view = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of()));

            var stageMilestones = cases.roadmap(view.id()).stages().get(0).milestones();
            assertThat(stageMilestones.get(0).dueDate()).isEqualTo(calendar.plusBusinessDays(LocalDate.now(), 2));
            assertThat(stageMilestones.get(1).dueDate()).isEqualTo(calendar.plusBusinessDays(LocalDate.now(), 5));
            assertThat(view.targetCompletionDate()).isEqualTo(calendar.plusBusinessDays(LocalDate.now(), 5));
        });
    }

    // ---- fixtures / helpers ------------------------------------------------

    /**
     * s1 (Onboarding) -> s2 (Legal Review, entry condition segment=ENTERPRISE) -> s3
     * (Go Live, unconditional). A segment=SMB case skips s2 via its own failed entry
     * condition rather than a branch rule.
     */
    private WorkflowDefinitionRequest threeStageOnboardingWithConditionalLegalReview() {
        var attrs = List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                List.of("SMB", "ENTERPRISE")));
        var s2Condition = new WorkflowDefinitionRequest.ConditionRequest(
                ConditionSource.ATTRIBUTE, "segment", ConditionOperator.EQ, "ENTERPRISE", null);
        var s2 = new WorkflowDefinitionRequest.StageRequest("s2", "Legal Review", null, false, true, true, null,
                WriteScope.ANY, null, s2Condition, null,
                List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("Do it")))), List.of());
        return new WorkflowDefinitionRequest(List.of(
                stage("s1", "Onboarding", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("Do it"))))),
                s2,
                stage("s3", "Go Live", List.of(milestone("m3", "M3", 1, List.of(), List.of(manual("Do it")))))
        ), attrs, 0L);
    }

    /**
     * The real, persisted id of the stage at this 0-based ordinal position --
     * WorkflowDefinitionView.key is echoed back as the stage's own id (see
     * JourneyFixtures.publish's javadoc), never the client-local key a request
     * declared, so stages are identified by their ordinal position here instead.
     */
    private UUID stageId(UUID caseId, int ordinalIndex) {
        return cases.roadmap(caseId).stages().get(ordinalIndex).id();
    }

    private void satisfyEveryRequirementOfCurrentStage(UUID caseId) {
        var view = cases.get(caseId);
        var stage = cases.roadmap(caseId).stages().stream()
                .filter(s -> s.id().equals(view.currentStageId()))
                .findFirst().orElseThrow();
        for (MilestoneRoadmapView m : stage.milestones()) {
            for (RequirementRoadmapView r : m.requirements()) {
                if (r.status() == RequirementStatus.OPEN) satisfyRequirement(r.id());
            }
        }
    }

    private void satisfyRequirement(UUID requirementId) {
        Requirement r = requirements.findById(requirementId).orElseThrow();
        r.setStatus(RequirementStatus.SATISFIED);
        r.setSatisfiedAt(Instant.now());
        requirements.save(r);
        reconcileAgain(r.getCaseId());
    }

    private void reconcileAgain(UUID caseId) {
        Case c = caseRepository.findById(caseId).orElseThrow();
        engine.reconcile(c);
    }
}
