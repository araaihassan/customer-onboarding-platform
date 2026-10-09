package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.scheduling.DigestJob;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6B spec 6.3: daily and weekly digests, judged in the tenant's zone on its working days, one digest
 * per user per period, each pending notification in at most one digest, and only what the recipient
 * can still see at digest time.
 */
@Import(FlakyEmail.class)
class DigestScheduleTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired NotificationTestSupport support;
    @Autowired DigestJob job;

    private static final Map<String, Scope> VIEWER = Map.of(
            PermissionKeys.TASK_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL);

    @AfterEach void reset() { FlakyEmail.reset(); }

    private record World(UUID t, UUID caseId, UUID user, String email, UUID role) {}

    private World world(String slug, String cadence) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        return withUser(new World(t, caseId, null, null, null), slug, "dana", cadence);
    }

    private World withUser(World w, String slug, String name, String cadence) {
        String email = name + "@" + slug + ".test";
        UUID u = fixture.runAsReturning(w.t(), () -> fixture.createUser(w.t(), email));
        UUID role = support.grant(w.t(), u, VIEWER);
        setCadence(w.t(), u, cadence);
        return new World(w.t(), w.caseId(), u, email, role);
    }

    private void setCadence(UUID t, UUID u, String cadence) {
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, ?, ?, now(), now()) on conflict (tenant_id, user_id) "
                + "do update set email_cadence = excluded.email_cadence", t, u, cadence);
    }

    /** A real task assignment, so the row arrives through the pipeline. */
    private void assign(World w, String title) {
        fixture.runAs(w.t(), () -> tasks.create(w.caseId(), new CreateTaskRequest(
                sla.milestoneIdAt(w.caseId(), 0), null, title, null, TaskPriority.MEDIUM, w.user(), null)));
    }

    private void advanceTo(ZoneId zone, DayOfWeek day, LocalTime time) {
        Instant now = clock.instant();
        ZonedDateTime target = now.atZone(zone).with(TemporalAdjusters.nextOrSame(day)).with(time);
        if (target.toInstant().isBefore(now)) target = target.plusWeeks(1);
        clock.advance(Duration.between(now, target.toInstant()));
    }

    private List<Map<String, Object>> digests(UUID t) {
        return support.outbox(t).stream().filter(r -> "DIGEST".equals(r.get("kind"))).toList();
    }

    private long count(String sql, Object... args) {
        return ownerJdbc().queryForObject(sql, Long.class, args);
    }

    private List<String> states(UUID t, UUID user) {
        return support.rowsFor(t, user).stream().map(r -> (String) r.get("email_state")).toList();
    }

    @Test
    void aDailyDigestArrivesAtEightOnAWorkingDayAndCarriesEveryPendingRow() {
        var w = world("dg-daily", "DAILY");
        assign(w, "Chase KYC one");
        assign(w, "Chase KYC two");
        assign(w, "Chase KYC three");
        assertThat(states(w.t(), w.user())).containsOnly("DIGEST_PENDING").hasSize(3);

        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(7, 55));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).isEmpty();

        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        var digest = digests(w.t());
        assertThat(digest).hasSize(1);
        assertThat(count("select count(*) from email_outbox_item where outbox_id = ?", digest.get(0).get("id"))).isEqualTo(3);
        assertThat(states(w.t(), w.user())).containsOnly("DIGESTED").hasSize(3);
        var mail = FlakyEmail.lastTo(w.email());
        assertThat(mail.subject()).isEqualTo("Your daily digest: 3 notifications");
        assertThat(mail.body()).contains("Chase KYC one", "Chase KYC two", "Chase KYC three")
                .contains("http://localhost:3000/t/");
    }

    @Test
    void aSecondRunTheSameDaySendsNothing() {
        var w = world("dg-twice", "DAILY");
        assign(w, "Chase KYC");
        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assign(w, "Chase again");
        clock.advance(Duration.ofHours(2));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).hasSize(1);
        assertThat(states(w.t(), w.user())).containsExactlyInAnyOrder("DIGESTED", "DIGEST_PENDING");
    }

    @Test
    void noPendingRowsMeansNoEmailButTheDayIsStillMarked() {
        var w = world("dg-empty", "DAILY");
        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).isEmpty();
        assertThat(FlakyEmail.sent).isEmpty();
        assertThat(count("select count(*) from notification_settings where user_id = ? and last_digest_at is not null",
                w.user())).isEqualTo(1);
    }

    @Test
    void aWeeklyDigestArrivesOnTheFirstWorkingDayOfTheWeek() {
        var w = world("dg-weekly", "WEEKLY");
        assign(w, "Chase KYC");
        advanceTo(ZoneOffset.UTC, DayOfWeek.MONDAY, LocalTime.of(9, 0));
        LocalDate monday = clock.instant().atZone(ZoneOffset.UTC).toLocalDate();
        ownerJdbc().update("insert into business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, ?, 'Bank holiday', now(), now())", w.t(), monday);
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).isEmpty();

        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 0));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(FlakyEmail.lastTo(w.email()).subject()).isEqualTo("Your weekly digest: 1 notification");
    }

    @Test
    void aWeeklyDigestDoesNotArriveMidWeek() {
        var w = world("dg-weekly-mid", "WEEKLY");
        assign(w, "Chase KYC");
        advanceTo(ZoneOffset.UTC, DayOfWeek.WEDNESDAY, LocalTime.of(9, 0));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).isEmpty();
    }

    @Test
    void noDigestOnANonWorkingDay() {
        var w = world("dg-sat", "DAILY");
        assign(w, "Chase KYC");
        advanceTo(ZoneOffset.UTC, DayOfWeek.SATURDAY, LocalTime.of(9, 0));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).isEmpty();
        assertThat(states(w.t(), w.user())).containsOnly("DIGEST_PENDING");
    }

    @Test
    void aDailyDigestFollowsTheTenantZone() {
        var w = world("dg-auckland", "DAILY");
        ZoneId auckland = ZoneId.of("Pacific/Auckland");
        ownerJdbc().update("update business_calendar set timezone = 'Pacific/Auckland' where tenant_id = ?", w.t());
        assign(w, "Chase KYC");

        advanceTo(auckland, DayOfWeek.TUESDAY, LocalTime.of(7, 55));
        assertThat(job.runOne(w.t())).isZero();

        advanceTo(auckland, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(clock.instant().atZone(ZoneOffset.UTC).getHour()).isNotEqualTo(8);
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(digests(w.t())).hasSize(1);
    }

    @Test
    void switchingToImmediateFlushesPendingOnce() {
        var w = world("dg-switch", "DAILY");
        assign(w, "Chase one");
        assign(w, "Chase two");
        setCadence(w.t(), w.user(), "IMMEDIATE");
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(digests(w.t())).hasSize(1);
        assertThat(FlakyEmail.lastTo(w.email()).body()).contains("Chase one", "Chase two");
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).hasSize(1);

        assign(w, "Chase three");
        var outbox = support.outbox(w.t());
        assertThat(outbox.get(outbox.size() - 1).get("kind")).isEqualTo("NOTIFICATION");
        assertThat(digests(w.t())).hasSize(1);
    }

    @Test
    void anInactiveUsersPendingRowsAreNotEmailed() {
        var w = world("dg-inactive", "DAILY");
        var other = withUser(w, "dg-inactive", "erin", "DAILY");   // positive control
        assign(w, "Chase KYC");
        assign(other, "Chase for erin");
        ownerJdbc().update("update app_user set status = 'DEACTIVATED' where id = ?", w.user());
        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(FlakyEmail.recipients()).containsExactly(other.email());
        assertThat(digests(w.t())).hasSize(1);
        assertThat(states(w.t(), w.user())).containsOnly("DIGEST_PENDING");
    }

    @Test
    void aRecipientWhoLostAccessSinceTheRowWasWrittenGetsNoDigestOfIt() {
        var w = world("dg-revoked", "DAILY");
        var other = withUser(w, "dg-revoked", "erin", "DAILY");   // positive control
        assign(w, "Secret KYC for dana");
        assign(other, "Chase for erin");
        support.revoke(w.t(), w.user(), w.role());
        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(FlakyEmail.recipients()).containsExactly(other.email());
        assertThat(FlakyEmail.sent.get(0).body()).doesNotContain("Secret KYC");
        assertThat(states(w.t(), w.user())).containsOnly("DIGEST_PENDING");
    }

    @Test
    void aFailedSendDoesNotLoseTheNotificationsAndTheyAreNotDigestedAgain() {
        var w = world("dg-fail", "DAILY");
        assign(w, "Chase KYC");
        FlakyEmail.failing.set(true);
        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        assertThat(job.runOne(w.t())).isEqualTo(1);
        assertThat(FlakyEmail.sent).isEmpty();
        assertThat(digests(w.t())).hasSize(1);
        assertThat(digests(w.t()).get(0).get("status")).isEqualTo("PENDING");
        FlakyEmail.failing.set(false);
        clock.advance(Duration.ofHours(1));
        assertThat(job.runOne(w.t())).isZero();
        assertThat(digests(w.t())).hasSize(1);
        assertThat(FlakyEmail.recipients()).containsExactly(w.email());
    }
}
