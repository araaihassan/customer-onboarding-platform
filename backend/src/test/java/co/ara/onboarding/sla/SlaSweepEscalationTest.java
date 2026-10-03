package co.ara.onboarding.sla;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.scheduling.TenantJobRunner;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 6.1 steps 2-3, invariant 5: overdue work escalates once per subject and due date. */
class SlaSweepEscalationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired SlaSweepService sweep;
    @Autowired TenantJobRunner runner;
    @Autowired BusinessCalendar calendar;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private List<RaisedEscalation> escalate(UUID t) {
        List<RaisedEscalation> raised = new ArrayList<>();
        runner.forTenant("sla-sweep", t, x -> raised.addAll(sweep.escalateOverdue()));
        return raised;
    }

    private LocalDate today(UUID t) { return fixture.runAsReturning(t, () -> calendar.today()); }

    private UUID task(UUID t, UUID caseId, UUID assignee) {
        return fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, assignee, null)).id());
    }

    private void due(UUID task, LocalDate d) {
        ownerJdbc().update("update task set due_date = ? where id = ?", d, task);
    }

    private List<Map<String, Object>> rows(UUID subject) {
        return ownerJdbc().queryForList(
                "select * from escalation where subject_id = ? order by due_date_at_escalation", subject);
    }

    @Test
    void anOverdueTaskEscalatesOnce() {
        UUID t = fixture.createTenant("esc-once");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID who = fixture.runAsReturning(t, () -> fixture.createUser(t, "late@x.test"));
        UUID task = task(t, caseId, who);
        due(task, today(t).minusDays(7));
        List<RaisedEscalation> first = escalate(t);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).subjectType()).isEqualTo(EscalationSubject.TASK);
        assertThat(first.get(0).caseName()).isEqualTo("SLA case");
        assertThat(rows(task)).hasSize(1);
        assertThat(rows(task).get(0).get("subject_type")).isEqualTo("TASK");
        assertThat(rows(task).get(0).get("late_user_id")).isEqualTo(who);
        assertThat(escalate(t)).isEmpty();
        assertThat(rows(task)).hasSize(1);
    }

    @Test
    void aTaskDueTodayDoesNotEscalate() {
        UUID t = fixture.createTenant("esc-today");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID task = task(t, caseId, null);
        due(task, today(t));
        assertThat(escalate(t)).isEmpty();
        assertThat(rows(task)).isEmpty();
    }

    @Test
    void cancelledAndCompletedTasksNeverEscalate() {
        UUID t = fixture.createTenant("esc-closed");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID done = task(t, caseId, null);
        UUID cancelled = task(t, caseId, null);
        LocalDate old = today(t).minusDays(7);
        due(done, old);
        due(cancelled, old);
        ownerJdbc().update("update task set status = 'COMPLETED', completed_at = now() where id = ?", done);
        ownerJdbc().update("update task set status = 'CANCELLED', cancelled_at = now(), "
                + "cancellation_reason = 'x' where id = ?", cancelled);
        assertThat(escalate(t)).isEmpty();
    }

    @Test
    void anOverdueMilestoneEscalatesToItsOwnerElseTheCaseOwner() {
        UUID t = fixture.createTenant("esc-ms");
        UUID caseId = fixture.runAsReturning(t, () -> sla.twoStageCaseWithSla(t, 5, 5));
        UUID msOwner = fixture.runAsReturning(t, () -> fixture.createUser(t, "ms@x.test"));
        UUID caseOwner = fixture.runAsReturning(t, () -> fixture.createUser(t, "co@x.test"));
        UUID m1 = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID m2 = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 1));
        LocalDate old = today(t).minusDays(7);
        ownerJdbc().update("update milestone set due_date = ?, owner_user_id = ? where id = ?", old, msOwner, m1);
        ownerJdbc().update("update milestone set due_date = ?, owner_user_id = null where id = ?", old, m2);
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", caseOwner, caseId);
        assertThat(escalate(t)).hasSize(2);
        assertThat(rows(m1).get(0).get("late_user_id")).isEqualTo(msOwner);
        assertThat(rows(m1).get(0).get("subject_type")).isEqualTo("MILESTONE");
        assertThat(rows(m2).get(0).get("late_user_id")).isEqualTo(caseOwner);
    }

    @Test
    void aBreachedClockEscalatesAfterThePolicyDelay() {
        UUID t = fixture.createTenant("esc-clock");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID clockId = sla.openClockId(caseId);
        Instant breached = Instant.now(clock).minus(Duration.ofDays(7));
        ownerJdbc().update("update sla_clock set breached_at = ? where id = ?", Timestamp.from(breached), clockId);
        assertThat(escalate(t)).hasSize(1);
        LocalDate expected = fixture.runAsReturning(t, () -> calendar.localDate(breached));
        assertThat(rows(clockId)).hasSize(1);
        assertThat(rows(clockId).get(0).get("subject_type")).isEqualTo("SLA_CLOCK");
        assertThat(rows(clockId).get(0).get("due_date_at_escalation").toString()).isEqualTo(expected.toString());

        UUID t2 = fixture.createTenant("esc-clock-fresh");
        UUID c2 = fixture.runAsReturning(t2, () -> sla.caseWithSla(t2, 5, true));
        ownerJdbc().update("update sla_clock set breached_at = ? where id = ?",
                Timestamp.from(Instant.now(clock)), sla.openClockId(c2));
        assertThat(escalate(t2)).isEmpty();
    }

    @Test
    void heldCasesAreSkipped() {
        UUID t = fixture.createTenant("esc-held");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID task = task(t, caseId, null);
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        due(task, today(t).minusDays(7));
        assertThat(escalate(t)).isEmpty();
    }

    @Test
    void aRedatedTaskCanEscalateAgain() {
        UUID t = fixture.createTenant("esc-redate");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID task = task(t, caseId, null);
        due(task, today(t).minusDays(7));
        assertThat(escalate(t)).hasSize(1);
        due(task, today(t).minusDays(14));
        assertThat(escalate(t)).hasSize(1);
        assertThat(rows(task)).hasSize(2);
        assertThat(escalate(t)).isEmpty();
        assertThat(rows(task)).hasSize(2);
    }

    @Test
    void overdueIsJudgedInTheTenantZone() {
        UUID t = fixture.createTenant("esc-zone");
        if (ownerJdbc().update("update business_calendar set timezone = 'Pacific/Auckland' where tenant_id = ?", t) == 0) {
            ownerJdbc().update("insert into business_calendar (id, tenant_id, timezone, created_at, updated_at) "
                    + "values (?, ?, 'Pacific/Auckland', now(), now())",
                    co.ara.onboarding.platform.Uuid7.generate(), t);
        }
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        for (int i = 0; i < 24 && !today(t).isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC))); i++) {
            clock.advance(Duration.ofHours(1));
        }
        LocalDate auckland = today(t);
        assertThat(auckland).isAfter(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        UUID dueToday = task(t, caseId, null);
        UUID dueOld = task(t, caseId, null);
        due(dueToday, auckland);
        due(dueOld, auckland.minusDays(7));
        escalate(t);
        assertThat(rows(dueToday)).isEmpty();
        assertThat(rows(dueOld)).hasSize(1);
    }

    @Test
    void anUnassignedOverdueTaskHasNoLateUserEvenWhenTheCaseHasAnOwner() {
        UUID t = fixture.createTenant("esc-unassigned");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@x.test"));
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", owner, caseId);
        UUID task = task(t, caseId, null);
        due(task, today(t).minusDays(7));
        assertThat(escalate(t)).hasSize(1);
        assertThat(rows(task).get(0).get("late_user_id")).isNull();
    }

    @Test
    void aBreachedClockUsesTheCaseOwnerAsTheLatePerson() {
        UUID t = fixture.createTenant("esc-clock-owner");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@x.test"));
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", owner, caseId);
        UUID clockId = sla.openClockId(caseId);
        ownerJdbc().update("update sla_clock set breached_at = ? where id = ?",
                Timestamp.from(Instant.now(clock).minus(Duration.ofDays(7))), clockId);
        assertThat(escalate(t)).hasSize(1);
        assertThat(rows(clockId).get(0).get("late_user_id")).isEqualTo(owner);
    }

    @Test
    void doneAndSkippedMilestonesNeverEscalate() {
        UUID t = fixture.createTenant("esc-ms-closed");
        UUID caseId = fixture.runAsReturning(t, () -> sla.twoStageCaseWithSla(t, 5, 5));
        UUID m1 = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID m2 = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 1));
        LocalDate old = today(t).minusDays(7);
        ownerJdbc().update("update milestone set due_date = ?, status = 'DONE' where id = ?", old, m1);
        ownerJdbc().update("update milestone set due_date = ?, status = 'SKIPPED' where id = ?", old, m2);
        assertThat(escalate(t)).isEmpty();
    }

    @Test
    void aMilestoneOnACompletedCaseNeverEscalates() {
        UUID t = fixture.createTenant("esc-case-done");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID m = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        ownerJdbc().update("update milestone set due_date = ? where id = ?", today(t).minusDays(7), m);
        ownerJdbc().update("update onboarding_case set status = 'COMPLETED' where id = ?", caseId);
        assertThat(escalate(t)).isEmpty();
    }

    @Test
    void aStoppedButBreachedClockNeverEscalates() {
        UUID t = fixture.createTenant("esc-clock-stopped");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID clockId = sla.openClockId(caseId);
        ownerJdbc().update("update sla_clock set breached_at = ?, stopped_at = now(), outcome = 'BREACHED' where id = ?",
                Timestamp.from(Instant.now(clock).minus(Duration.ofDays(7))), clockId);
        assertThat(escalate(t)).isEmpty();
    }

    @Test
    void escalationIsAuditedButNotOnTheTimeline() {
        UUID t = fixture.createTenant("esc-audit");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID task = task(t, caseId, null);
        due(task, today(t).minusDays(7));
        escalate(t);
        UUID escId = (UUID) rows(task).get(0).get("id");
        List<Map<String, Object>> audit = ownerJdbc().queryForList(
                "select timeline_visible, actor_type from audit_event "
                        + "where action = 'escalation.raised' and resource_id = ?", escId);
        assertThat(audit).hasSize(1);
        assertThat(audit.get(0).get("timeline_visible")).isEqualTo(false);
        assertThat(audit.get(0).get("actor_type")).isEqualTo("SYSTEM");
        escalate(t);
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where action = 'escalation.raised' and resource_id = ?",
                Long.class, escId)).isEqualTo(1L);
    }
}
