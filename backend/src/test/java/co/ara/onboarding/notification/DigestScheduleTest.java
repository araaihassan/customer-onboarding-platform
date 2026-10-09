package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.scheduling.DigestJob;
import co.ara.onboarding.task.CommentResourceType;
import co.ara.onboarding.task.CommentService;
import co.ara.onboarding.task.CreateCommentRequest;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowService;
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
    @Autowired CommentService comments;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;

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

        advanceTo(auckland, DayOfWeek.TUESDAY, LocalTime.of(7, 59, 59));
        assertThat(job.runOne(w.t())).isZero();

        clock.advance(Duration.ofSeconds(2));   // exactly 08:00:01 Auckland
        assertThat(clock.instant().atZone(auckland).toLocalTime().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
                .isEqualTo(LocalTime.of(8, 0, 1));
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

    private UUID tenantWithConfig(String slug) {
        UUID t = fixture.createTenant(slug);
        ownerJdbc().update("insert into business_calendar (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        ownerJdbc().update("insert into notification_policy (id, tenant_id, created_at, updated_at) "
                + "values (gen_random_uuid(), ?, now(), now()) on conflict (tenant_id) do nothing", t);
        return t;
    }

    private UUID dailyUser(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, grants);
        setCadence(t, u, "DAILY");
        return u;
    }

    private static final Map<String, Scope> COMMENTER = Map.of(
            PermissionKeys.COMMENT_CREATE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
            PermissionKeys.CASE_VIEW, Scope.ALL);

    /** A comment row is gated on the commented task's task.view, as the pipeline gated it: case.view alone is not enough. */
    @Test
    void aTaskCommentIsWithheldFromARecipientWhoLostTaskViewButStillHoldsCaseView() {
        UUID t = tenantWithConfig("dg-comment");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID manager = fixture.runAsReturning(t, () -> fixture.createUser(t, "manager@dg-comment.test"));
        support.grant(t, manager, Map.of(PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.USER_VIEW, Scope.ALL));
        UUID bob = fixture.runAsReturning(t, () -> fixture.createUser(t, "bob@dg-comment.test"));
        UUID bobsRole = support.grant(t, bob, COMMENTER);
        setCadence(t, bob, "DAILY");
        UUID eve = dailyUser(t, "eve@dg-comment.test", COMMENTER);          // positive control
        UUID carol = dailyUser(t, "carol@dg-comment.test", COMMENTER);
        UUID[] task = new UUID[1];
        fixture.runAsUser(t, manager, () -> task[0] = tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Chase the KYC pack", null, TaskPriority.MEDIUM, bob, null)).id());
        fixture.runAsUser(t, eve, () -> comments.create(caseId, new CreateCommentRequest(
                CommentResourceType.TASK, task[0], "Eve opened the thread")));
        fixture.runAsUser(t, carol, () -> comments.create(caseId, new CreateCommentRequest(
                CommentResourceType.TASK, task[0], "Carol says the customer is unhappy")));
        // bob keeps case.view but loses task.view for the task.
        support.revoke(t, bob, bobsRole);
        support.grant(t, bob, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));

        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        job.runOne(t);

        assertThat(FlakyEmail.recipients()).contains("eve@dg-comment.test").doesNotContain("bob@dg-comment.test");
        assertThat(FlakyEmail.lastTo("eve@dg-comment.test").body()).contains("New comment on \"Chase the KYC pack\"");
        assertThat(states(t, bob)).contains("DIGEST_PENDING").doesNotContain("DIGESTED");
    }

    /** WORKFLOW_PUBLISHED rows have no case id; they are gated on a case of that template the owner can view. */
    @Test
    void aWorkflowPublishedRowReachesADailyOwnerWhoCanSeeTheCaseAndNotOneWhoCannot() {
        UUID t = tenantWithConfig("dg-wf");
        UUID alice = dailyUser(t, "alice@dg-wf.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID bob = fixture.runAsReturning(t, () -> fixture.createUser(t, "bob@dg-wf.test"));
        UUID bobsRole = support.grant(t, bob, Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        setCadence(t, bob, "DAILY");
        for (UUID u : List.of(alice, bob)) {   // email is opt-in for this type
            ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, "
                    + "created_at, updated_at) values (gen_random_uuid(), ?, ?, 'WORKFLOW_PUBLISHED', true, true, now(), now())", t, u);
        }
        UUID templateId = fixture.runAsReturning(t, () -> journey.publishedTemplate());
        for (UUID owner : List.of(alice, bob)) {
            fixture.runAs(t, () -> {
                UUID customer = fixture.createCustomerOwnedBy(t, "Cust " + owner, owner);
                cases.create(new CreateCaseRequest(customer, templateId, "Case " + owner, Map.of()));
            });
        }
        fixture.runAs(t, () -> publishService.publish(workflows.createDraft(templateId)));
        assertThat(states(t, alice)).containsExactly("DIGEST_PENDING");
        assertThat(states(t, bob)).containsExactly("DIGEST_PENDING");
        support.revoke(t, bob, bobsRole);

        advanceTo(ZoneOffset.UTC, DayOfWeek.TUESDAY, LocalTime.of(8, 5));
        job.runOne(t);

        assertThat(FlakyEmail.recipients()).containsExactly("alice@dg-wf.test");
        assertThat(FlakyEmail.lastTo("alice@dg-wf.test").body()).contains("is live");
        assertThat(states(t, alice)).containsExactly("DIGESTED");
        assertThat(states(t, bob)).containsExactly("DIGEST_PENDING");
    }

    @Test
    void theSendTimeIsEightOClockWallClockOnADstChangeDay() {
        // Pacific/Auckland springs forward on Sunday 2026-09-27 (02:00 NZST -> 03:00 NZDT). That day's
        // midnight is 2026-09-26T12:00Z (UTC+12) but 08:00 wall-clock is 2026-09-26T19:00Z (UTC+13);
        // "midnight + 8h" would say 20:00Z, an hour late. Every weekday is working so Sunday counts.
        var w = world("dg-dst", "DAILY");
        ownerJdbc().update("update business_calendar set timezone = 'Pacific/Auckland', "
                + "working_days = '{1,2,3,4,5,6,7}' where tenant_id = ?", w.t());
        assign(w, "Chase KYC");
        Instant target = Instant.parse("2026-09-26T19:00:00Z");
        clock.advance(Duration.between(clock.instant(), target.minusSeconds(300)));
        assertThat(job.runOne(w.t())).isZero();
        clock.advance(Duration.ofSeconds(600));
        assertThat(job.runOne(w.t())).isEqualTo(1);
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
