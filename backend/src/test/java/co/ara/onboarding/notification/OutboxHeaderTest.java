package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-review minor (a): an email subject is a header. A CR/LF in it -- from a template subject, a
 * customer's name, a document's name -- must never reach the outbox, and it is clipped; and the
 * notification email carries the same clipped title and body the in-app row stores.
 */
class OutboxHeaderTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired OutboxWriter outbox;
    @Autowired NotificationPipeline pipeline;
    @Autowired NotificationTestSupport support;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;

    @Test
    void anOutboxSubjectNeverCarriesALineBreakAndIsClipped() {
        UUID t = fixture.createTenant("outbox-crlf");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@outbox-crlf.test"));
        fixture.runAs(t, () -> {
            outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, "u@outbox-crlf.test", u, null, null,
                    null, "Acme Ltd\r\nBcc: attacker@example.test", "line one\nline two", "/t/x"));
            outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, "u@outbox-crlf.test", u, null, null,
                    null, "x".repeat(400), "b", "/t/x"));
        });
        var rows = support.outbox(t);
        assertThat(rows.get(0).get("subject")).isEqualTo("Acme Ltd Bcc: attacker@example.test");
        assertThat(rows.get(0).get("body")).as("a body keeps its lines").isEqualTo("line one\nline two");
        assertThat((String) rows.get(1).get("subject")).hasSizeLessThanOrEqualTo(200);
    }

    @Test
    void aNotificationEmailCarriesTheStoredClippedTitleAndBody() {
        UUID t = fixture.createTenant("outbox-clip");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@outbox-clip.test"));
        support.grant(t, u, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID taskId = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        var draft = new NotificationPipeline.Draft(NotificationType.TASK_ASSIGNED, "task", taskId, caseId,
                "Task for Acme\r\nBcc: attacker@example.test " + "t".repeat(200), "b".repeat(700), "/t/x", Tone.INFO, null);
        fixture.runAs(t, () -> pipeline.deliver(draft, List.of(u), null,
                new NotificationPipeline.Visibility(PermissionKeys.TASK_VIEW, Task.class, taskId)));

        var row = support.rowsFor(t, u).get(0);
        var mail = support.outbox(t).get(0);
        assertThat((String) mail.get("subject")).doesNotContain("\r").doesNotContain("\n");
        assertThat(mail.get("subject")).isEqualTo(row.get("title"));
        assertThat(mail.get("body")).isEqualTo(row.get("body"));
        assertThat((String) row.get("title")).hasSizeLessThanOrEqualTo(120);
        assertThat((String) row.get("body")).hasSizeLessThanOrEqualTo(500);
    }
}
