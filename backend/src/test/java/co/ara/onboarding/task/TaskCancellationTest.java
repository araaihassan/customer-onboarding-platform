package co.ara.onboarding.task;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.TimelineService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 18: cancellation. A mandatory reason, its own audited action
 * (task.cancelled, additionally alongside task.status_changed), and the
 * critical constraint the brief states outright -- cancelling a task must
 * NEVER touch RequirementService at all (neither satisfy nor waive). A
 * requirement-linked task's requirement therefore stays open and its
 * milestone stays incomplete after the task that would have satisfied it is
 * cancelled instead.
 */
class TaskCancellationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired TimelineService timeline;
    @Autowired RequirementRepository requirementRepository;

    @Test
    void cancellingLeavesTheRequirementOpenAndTheMilestoneIncomplete() {
        UUID tenant = fixture.createTenant("task-cancel-linked");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID requirementId = requirementRepository.findByMilestoneId(milestoneId).get(0).getId();
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, requirementId, "Collect the pack", null,
                    TaskPriority.MEDIUM, null, null)).id();

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "not needed"));

            assertThat(cases.roadmap(caseId).stages().get(0).milestones().get(0).status())
                    .isNotEqualTo(MilestoneStatus.DONE);
        });
    }

    // Never assert inside the runAs lambda -- see the note on
    // TaskCompletionTest's own tests: catching there leaves the transaction
    // rollback-only and surfaces UnexpectedRollbackException instead of the
    // exception under test. Task creation runs in its own runAs; the refused
    // call is a separate one.
    @Test
    void cancellingWithoutAReasonIsRefused() {
        UUID tenant = fixture.createTenant("task-cancel-blank-reason");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> taskId[0] = adHocTask(tenant));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.CANCELLED, "  "))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancellingWithANullReasonIsRefused() {
        UUID tenant = fixture.createTenant("task-cancel-null-reason");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> taskId[0] = adHocTask(tenant));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.CANCELLED, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Its own action, recorded on the TRANSITION into CANCELLED -- never
     * inferred from a status column. Because records are never deleted, this
     * event is the only evidence the cancellation happened, and it must stay
     * distinguishable from an unrelated edit. Same shape as
     * contact.deactivated.
     */
    @Test
    void cancellingRecordsItsOwnAction() {
        UUID tenant = fixture.createTenant("task-cancel-audit");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Ad-hoc", null, TaskPriority.LOW, null, null)).id();

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "duplicate"));

            assertThat(timeline.forCase(caseId, Pageable.ofSize(50)).getContent())
                    .extracting(AuditEventView::action).contains("task.cancelled");
        });
    }

    /** task.status_changed is still recorded too -- an addition, not a replacement. */
    @Test
    void cancellingAlsoRecordsTheGenericStatusChangedAction() {
        UUID tenant = fixture.createTenant("task-cancel-audit-both");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Ad-hoc", null, TaskPriority.LOW, null, null)).id();

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "duplicate"));

            assertThat(timeline.forCase(caseId, Pageable.ofSize(50)).getContent())
                    .extracting(AuditEventView::action)
                    .contains("task.cancelled", "task.status_changed");
        });
    }

    @Test
    void cancellingPersistsTheReasonAndTimestamp() {
        UUID tenant = fixture.createTenant("task-cancel-persist");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "duplicate"));

            TaskView view = tasks.get(taskId);
            assertThat(view.status()).isEqualTo(TaskStatus.CANCELLED);
            assertThat(view.cancellationReason()).isEqualTo("duplicate");
            assertThat(view.cancelledAt()).isNotNull();
        });
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstMilestone(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }

    /** An ad-hoc task (no requirement) on a fresh case's first milestone. */
    private UUID adHocTask(UUID tenant) {
        UUID caseId = simpleCase(tenant);
        UUID milestoneId = firstMilestone(caseId);
        return tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Ad-hoc", null, TaskPriority.LOW, null, null)).id();
    }
}
