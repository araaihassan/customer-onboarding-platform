package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 17: status transitions through {@code TaskService.changeStatus},
 * and completion routed through the existing gated
 * {@code RequirementService.satisfy} rather than a new engine caller.
 *
 * The five tests below mirror the brief; the ones after them (the "matrix"
 * group) exist because the brief's own illegal-transition test only proves
 * COMPLETED->IN_PROGRESS is refused, which is necessary but not sufficient to
 * prove the whole TRANSITIONS matrix is right -- see TaskService's own
 * javadoc for the edges this proves and refuses.
 */
class TaskCompletionTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired RoleService roles;
    @Autowired RequirementRepository requirementRepository;

    @Test
    void completingARequirementLinkedTaskSatisfiesItsRequirementAndAdvancesTheMilestone() {
        UUID tenant = fixture.createTenant("task-complete-linked");
        var caseId = new UUID[1];
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId[0]);
            UUID requirementId = requirementRepository.findByMilestoneId(milestoneId).get(0).getId();
            taskId[0] = tasks.create(caseId[0], new CreateTaskRequest(
                    milestoneId, requirementId, "Collect the pack", null,
                    TaskPriority.MEDIUM, null, null)).id();

            tasks.changeStatus(taskId[0], new TaskStatusRequest(TaskStatus.COMPLETED, null));

            assertThat(cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).status())
                    .isEqualTo(MilestoneStatus.DONE);
        });
    }

    @Test
    void completingAnAdHocTaskLeavesTheCaseUntouched() {
        UUID tenant = fixture.createTenant("task-complete-adhoc");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID adHocTaskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.LOW, null, null)).id();

            int before = cases.get(caseId).progressPercent();
            tasks.changeStatus(adHocTaskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));

            assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
        });
    }

    /**
     * Holds task.complete at ALL but NOT milestone.complete. The refusal
     * comes from RequirementService.satisfy's own gate, across the bean
     * boundary -- see the design spec's §5.2 and TaskService.changeStatus's
     * own javadoc.
     */
    @Test
    void taskCompleteWithoutMilestoneCompleteIsRefusedOnARequirementLinkedTask() {
        UUID tenant = fixture.createTenant("task-complete-narrow-linked");
        var narrowUser = new UUID[1];
        var requirementLinkedTaskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            narrowUser[0] = fixture.createUser(tenant, "narrow-linked@example.com");
            grant(narrowUser[0], Map.of(
                    PermissionKeys.TASK_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID requirementId = requirementRepository.findByMilestoneId(milestoneId).get(0).getId();
            requirementLinkedTaskId[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, requirementId, "Collect the pack", null,
                    TaskPriority.MEDIUM, null, null)).id();
        });

        // Never assert inside the runAs/runAsUser lambda -- see the other
        // task tests' own note: catching there leaves the transaction
        // rollback-only and surfaces UnexpectedRollbackException instead of
        // the exception under test.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowUser[0], () ->
                tasks.changeStatus(requirementLinkedTaskId[0],
                        new TaskStatusRequest(TaskStatus.COMPLETED, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theSameActorMayCompleteAnAdHocTaskWithoutMilestoneComplete() {
        UUID tenant = fixture.createTenant("task-complete-narrow-adhoc");
        var narrowUser = new UUID[1];
        var adHocTaskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            narrowUser[0] = fixture.createUser(tenant, "narrow-adhoc@example.com");
            grant(narrowUser[0], Map.of(
                    PermissionKeys.TASK_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            adHocTaskId[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Ad-hoc follow-up", null,
                    TaskPriority.LOW, null, null)).id();
        });

        fixture.runAsUser(tenant, narrowUser[0], () ->
                tasks.changeStatus(adHocTaskId[0], new TaskStatusRequest(TaskStatus.COMPLETED, null)));

        fixture.runAs(tenant, () ->
                assertThat(tasks.get(adHocTaskId[0]).status()).isEqualTo(TaskStatus.COMPLETED));
    }

    @Test
    void anIllegalTransitionIsRefused() {
        UUID tenant = fixture.createTenant("task-complete-illegal");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            taskId[0] = adHocTask(tenant);
            tasks.changeStatus(taskId[0], new TaskStatusRequest(TaskStatus.COMPLETED, null));
        });

        // Never assert inside the runAs lambda -- see the note on the third
        // test above: catching there leaves the transaction rollback-only and
        // surfaces UnexpectedRollbackException instead of the exception under
        // test. The illegal call is therefore its own, separate runAs.
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.IN_PROGRESS, null))))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    // ---- the matrix beyond the brief's own single illegal-transition case ----
    //
    // COMPLETED->IN_PROGRESS (above) proves only that COMPLETED is terminal.
    // These prove the rest of TaskService.TRANSITIONS -- see its own javadoc
    // for the full reasoning, including why COMPLETED (like CANCELLED) turns
    // out to be reachable directly from every open state rather than only via
    // IN_PROGRESS: the brief's own required tests above complete a freshly
    // created (still-PENDING) task in a single call, with no prior transition
    // through IN_PROGRESS at all.
    //
    // CANCELLED is deliberately NOT exercised here -- Task 17's own required
    // tests never touch it either. See TaskCancellationTest (Task 18) for
    // cancellation's own reason validation, cancelledAt/cancellationReason
    // persistence, the task.cancelled audit action, and the requirement it
    // must leave untouched.

    @Test
    void pendingMayMoveToWaitingAndWaitingMayMoveToInProgress() {
        UUID tenant = fixture.createTenant("task-matrix-waiting-path");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.WAITING, null));
            assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.WAITING);

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.IN_PROGRESS, null));
            assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.IN_PROGRESS);
        });
    }

    @Test
    void waitingMayMoveDirectlyToCompleted() {
        UUID tenant = fixture.createTenant("task-matrix-waiting-completes");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);
            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.WAITING, null));

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));
            assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.COMPLETED);
        });
    }

    @Test
    void inProgressCannotMoveBackToPending() {
        UUID tenant = fixture.createTenant("task-matrix-backward");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            taskId[0] = adHocTask(tenant);
            tasks.changeStatus(taskId[0], new TaskStatusRequest(TaskStatus.IN_PROGRESS, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.PENDING, null))))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    void inProgressCannotMoveSidewaysToWaiting() {
        UUID tenant = fixture.createTenant("task-matrix-sideways");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            taskId[0] = adHocTask(tenant);
            tasks.changeStatus(taskId[0], new TaskStatusRequest(TaskStatus.IN_PROGRESS, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.WAITING, null))))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    void waitingCannotMoveBackToPending() {
        UUID tenant = fixture.createTenant("task-matrix-waiting-backward");
        var taskId = new UUID[1];
        fixture.runAs(tenant, () -> {
            taskId[0] = adHocTask(tenant);
            tasks.changeStatus(taskId[0], new TaskStatusRequest(TaskStatus.WAITING, null));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.changeStatus(taskId[0],
                new TaskStatusRequest(TaskStatus.PENDING, null))))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
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
