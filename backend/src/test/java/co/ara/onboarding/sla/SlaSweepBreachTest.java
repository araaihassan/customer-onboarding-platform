package co.ara.onboarding.sla;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.authz.SystemPrincipal;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.TimelineService;
import co.ara.onboarding.scheduling.TenantJobRunner;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 6.1 step 1, invariant 9: the sweep stamps a breach once, hides it from the timeline. */
class SlaSweepBreachTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired TimelineService timeline;
    @Autowired SlaSweepService sweep;
    @Autowired TenantJobRunner runner;
    @Autowired SlaClockRepository slaClocks;
    @Autowired co.ara.onboarding.authz.AuthorizedQuery authorizedQuery;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean SlaClockReader spiedReader;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private int stamp(UUID t) {
        int[] count = new int[1];
        runner.forTenant("sla-sweep", t, x -> count[0] = sweep.stampBreaches());
        return count[0];
    }

    private Instant breachedAt(UUID clockId) {
        return ownerJdbc().queryForObject("select breached_at from sla_clock where id = ?",
                java.sql.Timestamp.class, clockId) == null ? null
                : ownerJdbc().queryForObject("select breached_at from sla_clock where id = ?",
                        java.sql.Timestamp.class, clockId).toInstant();
    }

    @Test
    void aClockPastItsTargetIsStampedOnce() {
        UUID t = fixture.createTenant("sw-once");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID clockId = sla.openClockId(caseId);
        clock.advance(Duration.ofDays(4));
        assertThat(stamp(t)).isEqualTo(1);
        Instant first = breachedAt(clockId);
        assertThat(first).isNotNull();
        clock.advance(Duration.ofDays(1));
        assertThat(stamp(t)).isZero();
        assertThat(breachedAt(clockId)).isEqualTo(first);
    }

    @Test
    void aClockWithinTargetIsNotStamped() {
        UUID t = fixture.createTenant("sw-within");
        fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        assertThat(stamp(t)).isZero();
    }

    @Test
    void aPausedClockIsNotStampedForPausedTime() {
        UUID t = fixture.createTenant("sw-paused");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        clock.advance(Duration.ofDays(4));
        assertThat(stamp(t)).isZero();
    }

    @Test
    void breachIsAuditedAndHiddenFromTheTimeline() {
        UUID t = fixture.createTenant("sw-audit");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID clockId = sla.openClockId(caseId);
        clock.advance(Duration.ofDays(4));
        assertThat(stamp(t)).isEqualTo(1);
        List<Map<String, Object>> rows = ownerJdbc().queryForList(
                "select timeline_visible, actor_type, actor_user_id from audit_event "
                        + "where action = 'sla.breached' and resource_id = ?", clockId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("timeline_visible")).isEqualTo(false);
        assertThat(rows.get(0).get("actor_type")).isEqualTo("SYSTEM");
        assertThat(rows.get(0).get("actor_user_id")).isNull();
        List<String> actions = fixture.runAsReturning(t, () -> timeline.forCase(caseId, Pageable.ofSize(100))
                .getContent().stream().map(AuditEventView::action).toList());
        assertThat(actions).doesNotContain("sla.breached");
    }

    @Test
    void stoppedClocksAreNeverStamped() {
        UUID t = fixture.createTenant("sw-stopped");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        fixture.runAs(t, () -> requirements.satisfy(sla.firstRequirementId(caseId), null, null));
        clock.advance(Duration.ofDays(4));
        assertThat(stamp(t)).isZero();
    }

    @Test
    void weekendTimeDoesNotCountTowardsABreach() {
        UUID t = fixture.createTenant("sw-weekend");
        Instant sat = Instant.now(clock).atZone(ZoneOffset.UTC)
                .with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).toLocalDate().atStartOfDay(ZoneOffset.UTC)
                .plusHours(2).toInstant();
        clock.advance(Duration.between(Instant.now(clock), sat));
        fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        clock.advance(Duration.ofHours(36)); // Sunday 14:00: only weekend elapsed
        assertThat(stamp(t)).isZero();
        clock.advance(Duration.ofDays(2));   // Tuesday 14:00: a full Monday plus
        assertThat(stamp(t)).isEqualTo(1);
    }

    @Test
    void theSweepIsGatedByTheSystemActorsPermissionNotAnAdministrators() {
        UUID t = fixture.createTenant("sw-gate");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID clockId = sla.openClockId(caseId);
        clock.advance(Duration.ofDays(4));
        // No principal: the gate refuses.
        assertThatThrownBy(() -> fixture.runUnauthenticated(t, () -> sweep.stampBreaches()))
                .isInstanceOf(RuntimeException.class);
        assertThat(breachedAt(clockId)).isNull();
        // The system principal alone (no user_role rows) passes it and stamps.
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(t));
        int[] n = new int[1];
        fixture.runUnauthenticated(t, () -> n[0] = sweep.stampBreaches());
        assertThat(n[0]).isEqualTo(1);
        assertThat(breachedAt(clockId)).isNotNull();
    }

    @Test
    void exhaustedBeforeAPauseOpenedIsStillStamped() {
        UUID t = fixture.createTenant("sw-late-pause");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID clockId = sla.openClockId(caseId);
        clock.advance(Duration.ofDays(4));
        fixture.runAs(t, () -> cases.hold(caseId, "x"));
        assertThat(stamp(t)).isEqualTo(1);
        assertThat(breachedAt(clockId)).isNotNull();
    }

    // Race (i): the clock was stopped by a committed transaction after the sweep read it. The
    // conditional stamp must touch nothing, so no reopened clock and no audit row.
    @Test
    void aClockStoppedAfterTheSweepReadItIsNotStampedOrAudited() {
        UUID t = fixture.createTenant("sw-race-stop");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID clockId = sla.openClockId(caseId);
        clock.advance(Duration.ofDays(4));
        fixture.runAs(t, () -> requirements.satisfy(sla.firstRequirementId(caseId), null, null));
        int[] rows = new int[1];
        runner.forTenant("sla-sweep", t, x -> rows[0] = slaClocks.stampBreach(clockId, Instant.now(clock)));
        assertThat(rows[0]).isZero();
        assertThat(breachedAt(clockId)).isNull();
        assertThat(sla.openClockId(caseId)).isNull();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where action = 'sla.breached' and resource_id = ?",
                Long.class, clockId)).isZero();
        assertThat(ownerJdbc().queryForObject("select stopped_at is not null from sla_clock where id = ?",
                Boolean.class, clockId)).isTrue();
    }

    // Race (ii): the request loaded the clock, the sweep committed a stamp, then the request stops it.
    // The stop must not write the stale null back and must record the breach as the outcome.
    @Test
    void stoppingAClockNeverClobbersAConcurrentlyStampedBreach() {
        UUID t = fixture.createTenant("sw-race-stamp");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));
        UUID clockId = sla.openClockId(caseId);
        UUID req = fixture.runAsReturning(t, () -> sla.firstRequirementId(caseId));
        fixture.runAs(t, () -> {
            slaClocks.findById(clockId).orElseThrow(); // the request's stale snapshot (breached_at null)
            ownerJdbc().update("update sla_clock set breached_at = now() where id = ?", clockId);
            requirements.satisfy(req, null, null);
        });
        Instant stamped = breachedAt(clockId);
        assertThat(stamped).isNotNull();
        assertThat(ownerJdbc().queryForObject("select outcome from sla_clock where id = ?", String.class, clockId))
                .isEqualTo("BREACHED");
    }

    // After the stamp, a read in the SAME transaction must see breached_at (the sweep's next step
    // reads stamped clocks); a second clock in the batch is still stamped after the persistence clear.
    @Test
    void stampedClocksReadBackInTheSameTransactionAndTheRestOfTheBatchIsStamped() {
        UUID t = fixture.createTenant("sw-fresh");
        UUID c1 = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID c2 = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        clock.advance(Duration.ofDays(4));
        long[] seen = new long[2];
        runner.forTenant("sla-sweep", t, x -> {
            seen[0] = sweep.stampBreaches();
            seen[1] = authorizedQuery.findAll(slaClocks, SlaClock.class, co.ara.onboarding.authz.PermissionKeys.SLA_VIEW,
                    (r, q, cb) -> cb.isNotNull(r.get("breachedAt")), Pageable.unpaged())
                    .stream().filter(c -> c.getBreachedAt() != null).count();
        });
        assertThat(seen[0]).isEqualTo(2);
        assertThat(seen[1]).isEqualTo(2);
        assertThat(breachedAt(sla.openClockId(c1))).isNotNull();
        assertThat(breachedAt(sla.openClockId(c2))).isNotNull();
    }

    // The real sweep path: one clock is stopped by another committed transaction between the read
    // and its stamp. It gets no stamp and no audit row; the other clock in the batch is still stamped.
    @Test
    void aClockStoppedMidSweepIsSkippedWithoutAuditAndTheBatchContinues() {
        UUID t = fixture.createTenant("sw-mid");
        UUID c1 = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID c2 = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 1, true));
        UUID id1 = sla.openClockId(c1), id2 = sla.openClockId(c2);
        UUID doomed = id1.compareTo(id2) < 0 ? id1 : id2;   // read (and stamped) first: sorted by id
        UUID other = doomed.equals(id1) ? id2 : id1;
        clock.advance(Duration.ofDays(4));
        org.mockito.Mockito.doAnswer(inv -> {
            SlaClock c = inv.getArgument(0);
            if (doomed.equals(c.getId())) {   // elapsed() is the sweep's last read before the stamp
                ownerJdbc().update("update sla_clock set stopped_at = now(), outcome = 'BREACHED' where id = ?", doomed);
            }
            return inv.callRealMethod();
        }).when(spiedReader).elapsed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        assertThat(stamp(t)).isEqualTo(1);
        assertThat(breachedAt(doomed)).isNull();
        assertThat(breachedAt(other)).isNotNull();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where action = 'sla.breached' and resource_id = ?",
                Long.class, doomed)).isZero();
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where action = 'sla.breached' and resource_id = ?",
                Long.class, other)).isEqualTo(1);
    }
}
