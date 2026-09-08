package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
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

class HoldTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired MilestoneService milestones;
    @Autowired RequirementService requirements;
    @Autowired BusinessCalendar calendar;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void holdingACaseRefusesFurtherProgress() {
        UUID tenant = fixture.createTenant("hold-refuse");
        var requirementId = new AtomicReference<UUID>();
        UUID[] caseIdBox = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseIdBox[0] = simpleCase(tenant);
            requirementId.set(firstRequirementId(caseIdBox[0]));
            cases.hold(caseIdBox[0], "Waiting on the customer's bank letter");
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                requirements.satisfy(requirementId.get(), null, null)))
                .isInstanceOf(CaseOnHoldException.class);
    }

    /**
     * Q8's pause, from this side. Resuming shifts every open milestone's due date
     * and the case's target completion by the elapsed business days.
     */
    @Test
    void resumingShiftsOpenDueDatesByTheHeldBusinessDays() {
        UUID tenant = fixture.createTenant("hold-shift");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            LocalDate originalDue = firstMilestone(caseId).dueDate();
            LocalDate originalTarget = cases.get(caseId).targetCompletionDate();

            cases.hold(caseId, "Waiting on the customer's bank letter");
            clock.advance(Duration.ofDays(7));            // one week, five business days
            cases.resume(caseId);

            assertThat(firstMilestone(caseId).dueDate())
                    .isEqualTo(calendar.plusBusinessDays(originalDue, 5));
            assertThat(cases.get(caseId).targetCompletionDate())
                    .isEqualTo(calendar.plusBusinessDays(originalTarget, 5));
            assertThat(cases.get(caseId).totalHoldDays()).isEqualTo(5);
        });
    }

    /** A completed milestone's date is history and must not move. */
    @Test
    void resumingDoesNotTouchAlreadyCompletedMilestones() {
        UUID tenant = fixture.createTenant("hold-done");
        fixture.runAs(tenant, () -> {
            UUID caseId = twoMilestoneCase(tenant);
            requirements.satisfy(requirementOf(caseId, 0), null, null);   // completes milestone 0
            LocalDate doneDueBefore = milestoneAt(caseId, 0).dueDate();
            assertThat(milestoneAt(caseId, 0).status()).isEqualTo(MilestoneStatus.DONE);

            cases.hold(caseId, "pause");
            clock.advance(Duration.ofDays(7));
            cases.resume(caseId);

            assertThat(milestoneAt(caseId, 0).dueDate()).isEqualTo(doneDueBefore);
        });
    }

    @Test
    void holdAccumulatesAcrossSeveralPauses() {
        UUID tenant = fixture.createTenant("hold-accum");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);

            cases.hold(caseId, "first pause");
            clock.advance(Duration.ofDays(7));
            cases.resume(caseId);

            cases.hold(caseId, "second pause");
            clock.advance(Duration.ofDays(7));
            cases.resume(caseId);

            assertThat(cases.get(caseId).totalHoldDays()).isEqualTo(10);
        });
    }

    @Test
    void holdingRequiresCaseHoldAndAReason() {
        UUID tenant = fixture.createTenant("hold-reason");
        var caseId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> caseId.set(simpleCase(tenant)));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> cases.hold(caseId.get(), "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The rework path. Task 7 rejects backward branches, so reopening is how
     * "verification failed, go back" happens.
     */
    @Test
    void reopeningAMilestoneMakesItActiveAgainAndReducesProgress() {
        UUID tenant = fixture.createTenant("reopen-progress");
        fixture.runAs(tenant, () -> {
            UUID caseId = twoMilestoneCase(tenant);
            requirements.satisfy(requirementOf(caseId, 0), null, null);
            requirements.satisfy(requirementOf(caseId, 1), null, null);
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(100);
            assertThat(cases.get(caseId).status()).isEqualTo(CaseStatus.COMPLETED);

            UUID secondMilestoneId = milestoneAt(caseId, 1).id();
            milestones.reopen(secondMilestoneId, "KYC pack was the wrong entity");

            assertThat(cases.get(caseId).progressPercent()).isLessThan(100);
            assertThat(cases.get(caseId).status()).isEqualTo(CaseStatus.ACTIVE);
            assertThat(milestoneAt(caseId, 1).status()).isEqualTo(MilestoneStatus.ACTIVE);
        });
    }

    @Test
    void reopeningRequiresAReasonAndIsAudited() {
        UUID tenant = fixture.createTenant("reopen-reason");
        var milestoneId = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> milestoneId.set(firstMilestone(simpleCase(tenant)).id()));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> milestones.reopen(milestoneId.get(), "  ")))
                .isInstanceOf(IllegalArgumentException.class);

        fixture.runAs(tenant, () -> {
            milestones.reopen(milestoneId.get(), "Wrong entity provided");
            assertThat(auditEvents.findAll()).anySatisfy(e ->
                    assertThat(e.getAction()).isEqualTo(AuditActions.MILESTONE_REOPENED.key()));
        });
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    /** One stage, two equally-weighted mandatory-manual-requirement milestones. */
    private UUID twoMilestoneCase(UUID tenant) {
        var stageRequest = stage("s1", "Stage One", List.of(
                milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it"))),
                milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))));
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return firstMilestone(caseId).requirements().get(0).id();
    }

    private UUID requirementOf(UUID caseId, int milestoneIndex) {
        return milestoneAt(caseId, milestoneIndex).requirements().get(0).id();
    }

    private MilestoneRoadmapView firstMilestone(UUID caseId) {
        return milestoneAt(caseId, 0);
    }

    private MilestoneRoadmapView milestoneAt(UUID caseId, int index) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(index);
    }
}
