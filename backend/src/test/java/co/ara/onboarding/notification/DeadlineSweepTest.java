package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.scheduling.NotificationSweepJob;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B spec 6.2: the notification sweep's task-overdue and deadline-approaching alerts, on tenant
 * horizons, judged in the tenant's zone, deduped so a repeat sweep is free and a moved date re-arms.
 */
class DeadlineSweepTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired TaskService tasks;
    @Autowired BusinessCalendar calendar;
    @Autowired NotificationSweepJob job;
    @Autowired NotificationTestSupport support;

    private static final Map<String, Scope> VIEWER = Map.of(
            PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL);

    private record World(UUID t, UUID caseId, UUID assignee) {}

    private World world(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID assignee = fixture.runAsReturning(t, () -> fixture.createUser(t, "dana@" + slug + ".test"));
        support.grant(t, assignee, VIEWER);
        seedConfig(t);
        return new World(t, caseId, assignee);
    }

    /** The fixture inserts the tenant directly; provisioning's calendar and policy rows are added here. */
    private void seedConfig(UUID t) {
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        for (String kind : List.of("TASK_DUE", "MILESTONE_DUE", "DOCUMENT_REQUEST_DUE")) leads(t, kind, 2);
    }

    private LocalDate today(UUID t) { return fixture.runAsReturning(t, () -> calendar.today()); }

    private LocalDate businessDaysAhead(UUID t, int n) {
        return fixture.runAsReturning(t, () -> calendar.plusBusinessDays(calendar.today(), n));
    }

    private UUID task(World w, UUID assignee, LocalDate due) {
        UUID id = fixture.runAsReturning(w.t(), () -> tasks.create(w.caseId(), new CreateTaskRequest(
                sla.milestoneIdAt(w.caseId(), 0), null, "Chase the KYC pack", null, TaskPriority.MEDIUM, assignee,
                null)).id());
        ownerJdbc().update("update task set due_date = ? where id = ?", due, id);
        return id;
    }

    private List<Map<String, Object>> rows(World w, UUID recipient, String type) {
        return support.rowsFor(w.t(), recipient).stream().filter(r -> type.equals(r.get("type"))).toList();
    }

    private List<Map<String, Object>> visible(World w, UUID recipient, String type) {
        return rows(w, recipient, type).stream().filter(r -> Boolean.TRUE.equals(r.get("in_app"))).toList();
    }

    private void leads(UUID t, String kind, int... days) {
        ownerJdbc().update("delete from deadline_horizon where tenant_id = ? and kind = ?", t, kind);
        for (int d : days) {
            ownerJdbc().update("insert into deadline_horizon (id, tenant_id, kind, lead_days, created_at, updated_at) "
                    + "values (gen_random_uuid(), ?, ?, ?, now(), now())", t, kind, d);
        }
    }

    @Test
    void anOverdueTaskNotifiesItsAssigneeOnce() {
        var w = world("ds-overdue");
        UUID id = task(w, w.assignee(), today(w.t()).minusDays(1));
        job.runOne(w.t());
        var rows = visible(w, w.assignee(), "TASK_OVERDUE");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("tone")).isEqualTo("WARN");
        assertThat(rows.get(0).get("dedupe_key")).isEqualTo("TASK_OVERDUE:" + id + ":" + today(w.t()).minusDays(1));
        job.runOne(w.t());
        assertThat(rows(w, w.assignee(), "TASK_OVERDUE")).hasSize(1);
    }

    @Test
    void movingTheDueDateReArmsTheOverdueNotice() {
        var w = world("ds-rearm");
        UUID id = task(w, w.assignee(), today(w.t()).minusDays(1));
        job.runOne(w.t());
        ownerJdbc().update("update task set due_date = ? where id = ?", today(w.t()).minusDays(3), id);
        job.runOne(w.t());
        var rows = rows(w, w.assignee(), "TASK_OVERDUE");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("dedupe_key")).contains(
                "TASK_OVERDUE:" + id + ":" + today(w.t()).minusDays(3));
    }

    @Test
    void aTaskDueWithinTheHorizonGetsOneDeadlineNotice() {
        var w = world("ds-horizon");
        UUID id = task(w, w.assignee(), businessDaysAhead(w.t(), 1));
        job.runOne(w.t());
        var rows = visible(w, w.assignee(), "DEADLINE_APPROACHING");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dedupe_key").toString()).startsWith("DEADLINE:TASK_DUE:" + id + ":").endsWith(":2");
        job.runOne(w.t());
        assertThat(rows(w, w.assignee(), "DEADLINE_APPROACHING")).hasSize(1);
        // Not yet due and well outside the horizon: nothing.
        UUID far = task(w, w.assignee(), businessDaysAhead(w.t(), 6));
        job.runOne(w.t());
        assertThat(rows(w, w.assignee(), "DEADLINE_APPROACHING")).extracting(r -> r.get("subject_id"))
                .doesNotContain(far);
    }

    @Test
    void whenSeveralLeadsMatchOnlyTheSmallestIsSentAndTheRestAreConsumed() {
        var w = world("ds-leads");
        leads(w.t(), "TASK_DUE", 1, 5);
        task(w, w.assignee(), businessDaysAhead(w.t(), 1));
        job.runOne(w.t());
        var all = rows(w, w.assignee(), "DEADLINE_APPROACHING");
        assertThat(all).hasSize(2);
        var sent = visible(w, w.assignee(), "DEADLINE_APPROACHING");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("dedupe_key").toString()).endsWith(":1");
        var marker = all.stream().filter(r -> !Boolean.TRUE.equals(r.get("in_app"))).toList();
        assertThat(marker).hasSize(1);
        assertThat(marker.get(0).get("dedupe_key").toString()).endsWith(":5");
        assertThat(marker.get(0).get("email_state")).isEqualTo("NONE");
        job.runOne(w.t());
        assertThat(rows(w, w.assignee(), "DEADLINE_APPROACHING")).hasSize(2);
        assertThat(visible(w, w.assignee(), "DEADLINE_APPROACHING")).hasSize(1);
    }

    @Test
    void aMilestoneAndADocumentRequestAreRemindedToTheirOwners() {
        var arranged = support.openRequestWithContact("ds-owners");
        UUID t = arranged.tenant();
        seedConfig(t);
        UUID requester = ownerJdbc().queryForObject(
                "select requested_by from document_request where id = ?", UUID.class, arranged.requestId());
        support.grant(t, requester, VIEWER);
        UUID msOwner = fixture.runAsReturning(t, () -> fixture.createUser(t, "mo@ds-owners.test"));
        support.grant(t, msOwner, VIEWER);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID ms = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        LocalDate due = businessDaysAhead(t, 1);
        ownerJdbc().update("update milestone set owner_user_id = ?, due_date = ? where id = ?", msOwner, due, ms);
        ownerJdbc().update("update document_request set due_at = ? where id = ?",
                Timestamp.from(due.atTime(12, 0).toInstant(ZoneOffset.UTC)), arranged.requestId());

        job.runOne(t);

        var w = new World(t, caseId, msOwner);
        var m = visible(w, msOwner, "DEADLINE_APPROACHING");
        assertThat(m).hasSize(1);
        assertThat(m.get(0).get("subject_type")).isEqualTo("milestone");
        assertThat(m.get(0).get("subject_id")).isEqualTo(ms);
        var r = visible(w, requester, "DEADLINE_APPROACHING");
        assertThat(r).hasSize(1);
        assertThat(r.get(0).get("subject_type")).isEqualTo("document_request");
        assertThat(r.get(0).get("subject_id")).isEqualTo(arranged.requestId());
        assertThat(r.get(0).get("title").toString()).startsWith("Contract is due");
    }

    @Test
    void aCompletedOrCancelledTaskIsNeverReminded() {
        var w = world("ds-closed");
        UUID done = task(w, w.assignee(), today(w.t()).minusDays(1));
        UUID gone = task(w, w.assignee(), businessDaysAhead(w.t(), 1));
        ownerJdbc().update("update task set status = 'COMPLETED' where id = ?", done);
        ownerJdbc().update("update task set status = 'CANCELLED', cancellation_reason = 'no longer needed' where id = ?", gone);
        job.runOne(w.t());
        assertThat(rows(w, w.assignee(), "TASK_OVERDUE")).isEmpty();
        assertThat(rows(w, w.assignee(), "DEADLINE_APPROACHING")).isEmpty();
    }

    @Test
    void deadlineIsJudgedInTheTenantZone() {
        // Monday 20:00 UTC is already Tuesday morning in Auckland (UTC+12/+13 either way).
        Instant mon = Instant.now(clock).atZone(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).plusHours(20).toInstant();
        clock.advance(Duration.between(Instant.now(clock), mon));
        var w = world("ds-zone");
        ownerJdbc().update("update business_calendar set timezone = 'Pacific/Auckland' where tenant_id = ?", w.t());
        LocalDate aucklandToday = today(w.t());
        LocalDate utcToday = Instant.now(clock).atZone(ZoneOffset.UTC).toLocalDate();
        assertThat(aucklandToday).isEqualTo(utcToday.plusDays(1));
        LocalDate due = businessDaysAhead(w.t(), 2);
        assertThat(due).isEqualTo(utcToday.plusDays(3));    // Thursday: 2 Auckland business days, 3 UTC ones
        UUID id = task(w, w.assignee(), due);

        job.runOne(w.t());

        var rows = visible(w, w.assignee(), "DEADLINE_APPROACHING");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("dedupe_key")).isEqualTo("DEADLINE:TASK_DUE:" + id + ":" + due + ":2");
    }

    @Test
    void anAssigneeWhoTurnedTheTypeOffGetsNothingButTheMarkersStillBurn() {
        var w = world("ds-optout");
        leads(w.t(), "TASK_DUE", 1, 5);
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, "
                + "email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'DEADLINE_APPROACHING', "
                + "false, false, now(), now())", w.t(), w.assignee());
        task(w, w.assignee(), businessDaysAhead(w.t(), 1));
        job.runOne(w.t());
        assertThat(visible(w, w.assignee(), "DEADLINE_APPROACHING")).isEmpty();
        assertThat(rows(w, w.assignee(), "DEADLINE_APPROACHING")).hasSize(1);    // the lead-5 marker only

        ownerJdbc().update("update notification_preference set in_app_enabled = true where user_id = ?", w.assignee());
        job.runOne(w.t());
        // The opt-in gets the current (smallest) notice; the consumed larger lead never fires late.
        var sent = visible(w, w.assignee(), "DEADLINE_APPROACHING");
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).get("dedupe_key").toString()).endsWith(":1");
    }

    @Test
    void aRecipientWhoCannotViewUnderTheirOwnScopeGetsNoTaskAlertButAMatchingOneDoes() {
        var w = world("ds-scope-task");
        UUID team = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), "team@ds-scope-task.test"));
        UUID dept = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), "dept@ds-scope-task.test"));
        UUID own = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), "own@ds-scope-task.test"));
        support.grant(w.t(), team, Map.of(PermissionKeys.TASK_VIEW, Scope.TEAM));
        support.grant(w.t(), dept, Map.of(PermissionKeys.TASK_VIEW, Scope.DEPARTMENT));
        support.grant(w.t(), own, Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED));
        for (UUID u : List.of(team, dept, own)) {
            task(w, u, today(w.t()).minusDays(1));
            task(w, u, businessDaysAhead(w.t(), 1));
        }
        job.runOne(w.t());                                // must not throw for narrower-than-ALL recipients
        for (UUID u : List.of(team, dept)) {
            assertThat(rows(w, u, "TASK_OVERDUE")).isEmpty();
            assertThat(rows(w, u, "DEADLINE_APPROACHING")).isEmpty();
        }
        assertThat(visible(w, own, "TASK_OVERDUE")).hasSize(1);
        assertThat(visible(w, own, "DEADLINE_APPROACHING")).hasSize(1);
    }

    @Test
    void aMilestoneOwnerWhoCannotViewTheCaseUnderTheirOwnScopeGetsNothingButAMatchingOneDoes() {
        var w = world("ds-scope-case");
        UUID team = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), "team@ds-scope-case.test"));
        UUID own = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), "own@ds-scope-case.test"));
        support.grant(w.t(), team, Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
        support.grant(w.t(), own, Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
        UUID ms0 = fixture.runAsReturning(w.t(), () -> sla.milestoneIdAt(w.caseId(), 0));
        UUID caseB = fixture.runAsReturning(w.t(), () -> sla.caseWithSla(w.t(), 5, true));
        UUID ms1 = fixture.runAsReturning(w.t(), () -> sla.milestoneIdAt(caseB, 0));
        LocalDate due = businessDaysAhead(w.t(), 1);
        ownerJdbc().update("update milestone set owner_user_id = ?, due_date = ? where id = ?", team, due, ms0);
        ownerJdbc().update("update milestone set owner_user_id = ?, due_date = ? where id = ?", own, due, ms1);
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", own, caseB);
        fixture.runAs(w.t(), () -> journey.addParticipant(w.t(), caseB, own, RelationshipType.OWNER,
                ParticipantStatus.ACTIVE));
        job.runOne(w.t());
        assertThat(rows(w, team, "DEADLINE_APPROACHING")).isEmpty();
        assertThat(visible(w, own, "DEADLINE_APPROACHING")).hasSize(1);
    }
}
