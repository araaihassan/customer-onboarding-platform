package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.UpdateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan amendment 12: every audit action is recorded before the calls that record its
 * consequences, and a notification is a consequence. One test per cause; each later producer
 * task adds its own, in a fresh tenant, and asserts through {@link #assertCauseFirst}.
 */
class NotificationOrderingTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired NotificationTestSupport support;

    /** Cause before effect: the cause's audit row is not later than the notification it led to. */
    private void assertCauseFirst(UUID tenant, String cause) {
        var causeAt = ownerJdbc().queryForObject(
                "select max(occurred_at) from audit_event where tenant_id = ? and action = ?",
                java.sql.Timestamp.class, tenant, cause);
        var effectAt = ownerJdbc().queryForObject(
                "select min(occurred_at) from audit_event where tenant_id = ? and action = 'notification.sent'",
                java.sql.Timestamp.class, tenant);
        assertThat(causeAt).isNotNull();
        assertThat(effectAt).isNotNull();
        assertThat(causeAt).isBeforeOrEqualTo(effectAt);
    }

    /** A user who may create and edit tasks (and resolve an assignee) at ALL. */
    private UUID taskManager(UUID t, String email) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, Map.of(
                PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.USER_VIEW, Scope.ALL));
        return u;
    }

    private UUID taskViewer(UUID t, String email) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        return u;
    }

    @Test
    void reassigningATaskIsRecordedBeforeItsNotification() {
        UUID t = fixture.createTenant("order-task-assigned");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID actor = taskManager(t, "actor@order-task-assigned.test");
        UUID carol = taskViewer(t, "carol@order-task-assigned.test");

        // Created unassigned, so the only notification.sent row is the reassignment's.
        UUID[] taskId = new UUID[1];
        fixture.runAsUser(t, actor, () -> taskId[0] = tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        fixture.runAsUser(t, actor, () -> tasks.update(taskId[0], new UpdateTaskRequest(
                "Chase", null, TaskPriority.MEDIUM, carol, null, milestoneId)));

        assertThat(support.rowsFor(t, carol)).hasSize(1);
        assertCauseFirst(t, "task.assigned");
    }
}
