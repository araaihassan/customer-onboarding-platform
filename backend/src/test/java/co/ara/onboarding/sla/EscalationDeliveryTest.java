package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.scheduling.TenantJobRunner;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.RecordingEmailSender;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 6.2-6.3: notifications are written with the sweep, email goes out in a second run after commit. */
class EscalationDeliveryTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired SlaSweepService sweep;
    @Autowired TenantJobRunner runner;
    @Autowired BusinessCalendar calendar;
    @Autowired RecordingEmailSender emails;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    /** An overdue, assigned task in a fresh tenant; returns {tenant, caseId, lateUser}. */
    private UUID[] overdue(String slug, String lateEmail) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID late = fixture.runAsReturning(t, () -> fixture.createUser(t, lateEmail));
        UUID task = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, late, null)).id());
        ownerJdbc().update("update task set due_date = ? where id = ?",
                fixture.runAsReturning(t, () -> calendar.today()).minusDays(7), task);
        return new UUID[] {t, caseId, late};
    }

    private void admin(UUID t, String email) {
        fixture.createAdminUser(t, email);
        ownerJdbc().update("update role set name = 'Administrator' where id = ?", fixture.administratorRoleId(t));
    }

    private List<Map<String, Object>> notifications(UUID t) {
        return ownerJdbc().queryForList("select * from notification where tenant_id = ?", t);
    }

    @Test
    void anEscalationNotifiesAndEmailsTheManager() {
        UUID[] x = overdue("del-mgr", "late@del-mgr.test");
        UUID boss = fixture.runAsReturning(x[0], () -> fixture.createUser(x[0], "boss@del-mgr.test"));
        ownerJdbc().update("update app_user set manager_id = ? where id = ?", boss, x[2]);
        sla.sweepAndEmail(x[0]);
        var rows = notifications(x[0]);
        assertThat(rows).hasSize(1);
        var n = rows.get(0);
        assertThat(n.get("type")).isEqualTo("ESCALATION");
        assertThat(n.get("recipient_user_id")).isEqualTo(boss);
        UUID customerId = ownerJdbc().queryForObject(
                "select customer_id from onboarding_case where id = ?", UUID.class, x[1]);
        assertThat(n.get("link_path")).isEqualTo("/t/del-mgr/customers/" + customerId + "/cases/" + x[1]);
        assertThat(n.get("escalation_id")).isNotNull();
        assertThat(n.get("emailed_at")).isNotNull();
        var mail = emails.lastTo("boss@del-mgr.test");
        assertThat(mail).isPresent();
        assertThat(mail.get().subject()).startsWith("Escalation:");
        assertThat(emails.lastTo("late@del-mgr.test")).isEmpty();
    }

    @Test
    void administratorsEachGetANotification() {
        UUID[] x = overdue("del-adm", "late@del-adm.test");
        admin(x[0], "a1@del-adm.test");
        fixture.createAdminUser(x[0], "a2@del-adm.test");
        sla.sweepAndEmail(x[0]);
        // The tenant fixture's own plumbing administrator holds the same role, so it is a recipient too.
        assertThat(notifications(x[0])).extracting(n -> n.get("recipient_user_id")).hasSize(3).doesNotHaveDuplicates()
                .doesNotContain(x[2]);
        assertThat(emails.lastTo("a1@del-adm.test")).isPresent();
        assertThat(emails.lastTo("a2@del-adm.test")).isPresent();
    }

    @Test
    void noActiveAdministratorStillRecordsTheEscalation() {
        UUID[] x = overdue("del-none", "late@del-none.test");
        assertThat(sla.escalationCount(x[0])).isZero();
        sla.sweepAndEmail(x[0]);
        assertThat(sla.escalationCount(x[0])).isEqualTo(1);
        assertThat(notifications(x[0])).isEmpty();
    }

    @Test
    void notificationSentIsAuditedOffTheTimeline() {
        UUID[] x = overdue("del-aud", "late@del-aud.test");
        admin(x[0], "a@del-aud.test");
        sla.sweepAndEmail(x[0]);
        var rows = ownerJdbc().queryForList(
                "select timeline_visible from audit_event where tenant_id = ? and action = 'notification.sent'", x[0]);
        assertThat(rows).hasSize(notifications(x[0]).size()).isNotEmpty();
        assertThat(rows).allSatisfy(r -> assertThat(r.get("timeline_visible")).isEqualTo(false));
    }

    @Test
    void aRolledBackSweepSendsNothing() {
        UUID[] x = overdue("del-rb", "late@del-rb.test");
        admin(x[0], "a@del-rb.test");
        assertThatThrownBy(() -> runner.forTenant("sla-sweep", x[0], t -> {
            sweep.sweep();
            throw new IllegalStateException("boom");
        })).isInstanceOf(RuntimeException.class);
        runner.forTenant("sla-email", x[0], t -> sweep.retryUnsentEmail());
        assertThat(emails.lastTo("a@del-rb.test")).isEmpty();
        assertThat(notifications(x[0])).isEmpty();
        assertThat(sla.escalationCount(x[0])).isZero();
    }

    @Test
    void anAlreadyEmailedNotificationIsNotSentAgain() {
        UUID[] x = overdue("del-once", "late@del-once.test");
        admin(x[0], "a@del-once.test");
        sla.sweepAndEmail(x[0]);
        int[] sent = {-1};
        runner.forTenant("sla-email", x[0], t -> sent[0] = sweep.retryUnsentEmail());
        assertThat(sent[0]).isZero();
    }

    @Test
    void aStaleNotificationIsAbandonedNotRetriedForever() {
        UUID[] x = overdue("del-stale", "late@del-stale.test");
        admin(x[0], "a@del-stale.test");
        runner.forTenant("sla-sweep", x[0], t -> sweep.sweep());
        ownerJdbc().update("update notification set created_at = now() - interval '10 days' where tenant_id = ?", x[0]);
        int[] sent = {-1};
        runner.forTenant("sla-email", x[0], t -> sent[0] = sweep.retryUnsentEmail());
        assertThat(sent[0]).isZero();
        assertThat(emails.lastTo("a@del-stale.test")).isEmpty();
    }
}
