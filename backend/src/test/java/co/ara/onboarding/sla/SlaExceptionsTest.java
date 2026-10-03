package co.ara.onboarding.sla;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 8, 9.2: the war room feed, scoped by the viewer's sla.view scope. */
class SlaExceptionsTest extends SecurityTestBase {

    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired DocumentRequestService requests;
    @Autowired SlaExceptionsService exceptions;

    /** Tuesday 12:00 UTC: half of the business day remains before tenant midnight. */
    private Instant now;

    @BeforeEach void pinNow() {
        ZonedDateTime n = clock.instant().atZone(ZoneOffset.UTC);
        ZonedDateTime tue = n.with(TemporalAdjusters.next(DayOfWeek.TUESDAY))
                .withHour(12).withMinute(0).withSecond(0).withNano(0);
        clock.advance(Duration.between(n, tue));
        now = tue.toInstant();
    }

    /** Moves a case's open clock to start at the given hours before Tuesday 12:00 (Monday-based). */
    private void startedAt(UUID caseId, Instant at) {
        ownerJdbc().update("update sla_clock set started_at = ? where case_id = ? and stopped_at is null",
                Timestamp.from(at), caseId);
    }

    private Instant monday(int hour) { return now.minus(Duration.ofDays(1)).atZone(ZoneOffset.UTC)
            .withHour(hour).toInstant(); }

    private void breach(UUID caseId) {
        ownerJdbc().update("update sla_clock set breached_at = ? where case_id = ? and stopped_at is null",
                Timestamp.from(now), caseId);
    }

    private void escalation(UUID t, UUID subject, String type, UUID caseId, UUID to, UUID late, Instant at,
                            LocalDate due) {
        ownerJdbc().update("""
                insert into escalation (id, tenant_id, subject_type, subject_id, case_id, late_user_id, route,
                    escalated_to_user_id, due_date_at_escalation, overdue_days, escalated_at, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, 'MANAGER', ?, ?, 2, ?, ?, ?)""",
                Uuid7.generate(), t, type, subject, caseId, late, to, due,
                Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }

    private ExceptionsView read(UUID t, UUID user) {
        var out = new AtomicReference<ExceptionsView>();
        fixture.runAsUser(t, user, () -> out.set(exceptions.exceptions()));
        return out.get();
    }

    private UUID breachedCase(UUID t) {
        UUID c = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        breach(c);
        return c;
    }

    @Test
    void breachedDueTodayAndWatchAreSeparated() {
        UUID t = fixture.createTenant("exc-cols");
        AppUser admin = fixture.createAdminUser(t, "a@exc-cols.example");
        UUID breached = breachedCase(t);
        UUID dueToday = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        startedAt(dueToday, monday(18));
        UUID watch = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 2, true));
        startedAt(watch, monday(10));
        fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true)); // comfortably within target

        ExceptionsView v = read(t, admin.getId());
        assertThat(v.breached()).extracting(ExceptionsView.Card::caseId).containsExactly(breached);
        assertThat(v.dueToday()).extracting(ExceptionsView.Card::caseId).containsExactly(dueToday);
        assertThat(v.watch()).extracting(ExceptionsView.Card::caseId).containsExactly(watch);
        ExceptionsView.Card card = v.breached().get(0);
        assertThat(card.caseName()).isEqualTo("SLA case");
        assertThat(card.customerName()).startsWith("Customer ");
        assertThat(card.stageName()).isEqualTo("Stage One");
        assertThat(v.calendarName()).isNotBlank();
    }

    @Test
    void summaryCountsMatch() {
        UUID t = fixture.createTenant("exc-sum");
        AppUser admin = fixture.createAdminUser(t, "a@exc-sum.example");
        UUID breached = breachedCase(t);
        UUID dueToday = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        startedAt(dueToday, monday(18));
        UUID held = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        fixture.runAs(t, () -> cases.hold(held, "waiting"));
        UUID mgr = fixture.runAsReturning(t, () -> fixture.createUser(t, "m@exc-sum.example"));
        UUID clockId = sla.openClockId(breached);
        escalation(t, clockId, "SLA_CLOCK", breached, mgr, null, now.minus(Duration.ofDays(1)), LocalDate.now().minusDays(1));
        escalation(t, Uuid7.generate(), "TASK", breached, mgr, null, now.minus(Duration.ofDays(8)), LocalDate.now().minusDays(9));

        ExceptionsView v = read(t, admin.getId());
        assertThat(v.summary().breached()).isEqualTo(v.breached().size()).isEqualTo(1);
        assertThat(v.summary().dueToday()).isEqualTo(v.dueToday().size()).isEqualTo(1);
        assertThat(v.summary().clocksPaused()).isEqualTo(1);
        assertThat(v.summary().autoEscalated()).isEqualTo(1);
    }

    @Test
    void cardsCarryEscalationHistoryNewestFirst() {
        UUID t = fixture.createTenant("exc-hist");
        AppUser admin = fixture.createAdminUser(t, "a@exc-hist.example");
        UUID breached = breachedCase(t);
        UUID mgr = fixture.runAsReturning(t, () -> fixture.createUser(t, "m@exc-hist.example"));
        UUID late = fixture.runAsReturning(t, () -> fixture.createUser(t, "l@exc-hist.example"));
        String mgrName = ownerJdbc().queryForObject("select full_name from app_user where id = ?", String.class, mgr);
        String lateName = ownerJdbc().queryForObject("select full_name from app_user where id = ?", String.class, late);
        escalation(t, Uuid7.generate(), "TASK", breached, mgr, late, now.minus(Duration.ofDays(3)), LocalDate.now().minusDays(4));
        escalation(t, sla.openClockId(breached), "SLA_CLOCK", breached, mgr, null, now.minus(Duration.ofHours(2)), LocalDate.now().minusDays(1));

        var notes = read(t, admin.getId()).breached().get(0).escalations();
        assertThat(notes).hasSize(2);
        assertThat(notes.get(0).subjectType()).isEqualTo(EscalationSubject.SLA_CLOCK);
        assertThat(notes.get(1).subjectType()).isEqualTo(EscalationSubject.TASK);
        assertThat(notes.get(0).escalatedToName()).isEqualTo(mgrName);
        assertThat(notes.get(1).latePersonName()).isEqualTo(lateName);
        assertThat(notes.get(1).overdueDays()).isEqualTo(2);
    }

    @Test
    void stoppedClocksAreNotExceptions() {
        UUID t = fixture.createTenant("exc-stop");
        AppUser admin = fixture.createAdminUser(t, "a@exc-stop.example");
        UUID c = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        ownerJdbc().update("update sla_clock set breached_at = ?, stopped_at = ?, outcome = 'BREACHED' where case_id = ?",
                Timestamp.from(now), Timestamp.from(now), c);
        ExceptionsView v = read(t, admin.getId());
        assertThat(v.breached()).isEmpty();
        assertThat(v.dueToday()).isEmpty();
        assertThat(v.watch()).isEmpty();
        assertThat(v.summary().breached()).isZero();
    }

    @Test
    void aMissingStageOrCustomerNameIsNullNotAnError() {
        UUID t = fixture.createTenant("exc-names");
        var viewer = new AtomicReference<AppUser>();
        fixture.runAs(t, () -> {
            AppUser u = fixture.createUserWithPassword(t, "v@exc-names.example", "long-enough-password");
            viewer.set(u);
            roles.assignRole(u.getId(), roles.createRole("War Room Only", "", Map.of(
                    PermissionKeys.SLA_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL)));
        });
        breachedCase(t);
        ExceptionsView v = read(t, viewer.get().getId());
        assertThat(v.breached()).hasSize(1);
        assertThat(v.breached().get(0).stageName()).isNull();
        assertThat(v.breached().get(0).customerName()).isNull();
        assertThat(v.breached().get(0).caseName()).isEqualTo("SLA case");
    }

    @Test
    void hasOpenRequestsReflectsOpenDocumentRequests() {
        UUID t = fixture.createTenant("exc-req");
        AppUser admin = fixture.createAdminUser(t, "a@exc-req.example");
        UUID withReq = breachedCase(t);
        UUID without = breachedCase(t);
        fixture.runAs(t, () -> requests.create(withReq,
                new CreateDocumentRequestRequest(DocumentCategory.OTHER, "desc", null, false, null)));
        ExceptionsView v = read(t, admin.getId());
        assertThat(v.breached()).filteredOn(c -> c.caseId().equals(withReq)).singleElement()
                .extracting(ExceptionsView.Card::hasOpenRequests).isEqualTo(true);
        assertThat(v.breached()).filteredOn(c -> c.caseId().equals(without)).singleElement()
                .extracting(ExceptionsView.Card::hasOpenRequests).isEqualTo(false);
    }

    @Test
    void aTeamScopedViewerNeverSeesAnotherTeamsClockOrEscalationAndTheStripExcludesThem() {
        UUID t = fixture.createTenant("exc-team");
        var viewer = new AtomicReference<AppUser>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();
        fixture.runAs(t, () -> {
            AppUser v = fixture.createUserWithPassword(t, "v@exc-team.example", "long-enough-password");
            viewer.set(v);
            UUID myTeam = fixture.createTeam(t, "Mine");
            UUID otherTeam = fixture.createTeam(t, "Other");
            fixture.addToTeam(t, v.getId(), myTeam);
            mine.set(sla.openFor(fixture.createCustomer(t, "Mine Co", null, null, myTeam)));
            theirs.set(sla.openFor(fixture.createCustomer(t, "Theirs Co", null, null, otherTeam)));
            roles.assignRole(v.getId(), roles.createRole("Sla Team", "", Map.of(
                    PermissionKeys.CASE_VIEW, Scope.TEAM, PermissionKeys.SLA_VIEW, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL, PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
        });
        UUID mgr = fixture.runAsReturning(t, () -> fixture.createUser(t, "m@exc-team.example"));
        for (UUID c : new UUID[]{mine.get(), theirs.get()}) {
            breach(c);
            escalation(t, sla.openClockId(c), "SLA_CLOCK", c, mgr, null, now.minus(Duration.ofDays(1)), LocalDate.now().minusDays(1));
        }
        ExceptionsView v = read(t, viewer.get().getId());
        assertThat(v.breached()).extracting(ExceptionsView.Card::caseId).containsExactly(mine.get());
        assertThat(v.breached().get(0).escalations()).hasSize(1);
        assertThat(v.summary().breached()).isEqualTo(1);
        assertThat(v.summary().autoEscalated()).isEqualTo(1);
        assertThat(v.toString()).doesNotContain(theirs.get().toString()).doesNotContain("Theirs Co");
    }

    @Test
    void anotherTenantsClocksNeverAppear() {
        UUID t1 = fixture.createTenant("exc-x1");
        UUID t2 = fixture.createTenant("exc-x2");
        AppUser admin2 = fixture.createAdminUser(t2, "a@exc-x2.example");
        breachedCase(t1);
        ExceptionsView v = read(t2, admin2.getId());
        assertThat(v.breached()).isEmpty();
        assertThat(v.summary().breached()).isZero();
        assertThat(v.calendarName()).isNotBlank();
    }

    @Test
    void httpAdministratorIs200AndUserWithoutSlaViewOrPortalActorIs403() throws Exception {
        UUID t = fixture.createTenant("exc-http");
        AppUser admin = fixture.createAdminUser(t, "a@exc-http.example");
        var plain = new AtomicReference<AppUser>();
        fixture.runAs(t, () -> {
            AppUser u = fixture.createUserWithPassword(t, "p@exc-http.example", "long-enough-password");
            plain.set(u);
            roles.assignRole(u.getId(), roles.createRole("Case Only", "", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL)));
        });
        AppUser portal = fixture.createPortalUser(t, "portal@exc-http.example");
        String url = "/api/t/exc-http/sla/exceptions";
        mvc.perform(as(get(url), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.calendarName", not(emptyOrNullString())))
                .andExpect(jsonPath("$.summary.breached").value(0));
        mvc.perform(as(get(url), plain.get())).andExpect(status().isForbidden());
        mvc.perform(as(get(url), portal)).andExpect(status().isForbidden());
    }
}
