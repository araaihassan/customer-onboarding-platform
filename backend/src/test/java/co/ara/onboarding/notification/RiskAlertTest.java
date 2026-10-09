package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RISK_CHANGED (6B spec 6.1, Q19): the sweep alerts the case audience once when a live clock first
 * falls within the at-risk threshold, and once more when it breaches. The clock is anchored to a
 * Monday 00:00 UTC so every advance below is a whole working-day count (default threshold 1.0 day).
 */
class RiskAlertTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired NotificationTestSupport support;

    private static final Map<String, Scope> COMPLETER = Map.of(
            PermissionKeys.MILESTONE_COMPLETE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
            PermissionKeys.WORKFLOW_VIEW, Scope.ALL);

    private record Arranged(UUID t, UUID caseId, UUID owner, UUID participant) {}

    private void anchorToMonday() {
        Instant mon = Instant.now(clock).atZone(ZoneOffset.UTC).with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                .toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        clock.advance(Duration.between(Instant.now(clock), mon));
    }

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        support.grant(t, u, grants);
        return u;
    }

    private Arranged arrange(String slug, boolean twoStage) {
        anchorToMonday();
        UUID t = fixture.createTenant(slug);
        UUID owner = user(t, "owner@" + slug + ".test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID caseId = fixture.runAsReturning(t, () -> twoStage
                ? sla.twoStageCaseWithSla(t, 3, 3)
                : sla.caseWithSla(t, 3, true));
        PostgresTestBase.ownerJdbcForSupport().update("update onboarding_case set owner_user_id = ? where id = ?",
                owner, caseId);
        UUID participant = user(t, "pat@" + slug + ".test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        fixture.runAs(t, () -> journey.addParticipant(t, caseId, participant, RelationshipType.PARTICIPANT,
                ParticipantStatus.ACTIVE));
        return new Arranged(t, caseId, owner, participant);
    }

    private List<Map<String, Object>> risk(Arranged a, UUID recipient) {
        return support.rowsFor(a.t(), recipient).stream()
                .filter(r -> "RISK_CHANGED".equals(r.get("type"))).toList();
    }

    @Test
    void aClockCrossingTheAtRiskThresholdAlertsOnce() {
        var a = arrange("ra-once", false);
        sla.sweepAndEmail(a.t());
        assertThat(risk(a, a.owner())).isEmpty();

        clock.advance(Duration.ofDays(2).plusHours(1));   // Wed 01:00: 0.96 of 3 days remain
        sla.sweepAndEmail(a.t());
        for (UUID u : List.of(a.owner(), a.participant())) {
            var rows = risk(a, u);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("tone")).isEqualTo("WARN");
            assertThat(rows.get(0).get("title")).isEqualTo("SLA case is at risk");
        }
        sla.sweepAndEmail(a.t());
        assertThat(risk(a, a.owner())).hasSize(1);
        assertThat(risk(a, a.participant())).hasSize(1);
    }

    @Test
    void aBreachAlertsOnceWithRiskTone() {
        var a = arrange("ra-breach", false);
        clock.advance(Duration.ofDays(2).plusHours(1));
        sla.sweepAndEmail(a.t());
        clock.advance(Duration.ofDays(1));                // Thu 01:00: past the 3-day target
        sla.sweepAndEmail(a.t());
        sla.sweepAndEmail(a.t());
        for (UUID u : List.of(a.owner(), a.participant())) {
            var rows = risk(a, u);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(1).get("tone")).isEqualTo("RISK");
            assertThat(rows.get(1).get("title")).isEqualTo("SLA case breached its SLA");
        }
    }

    @Test
    void aClockThatBreachesBeforeAnyAtRiskSweepSendsOnlyTheBreach() {
        var a = arrange("ra-jump", false);
        clock.advance(Duration.ofDays(4));                // Fri 00:00
        sla.sweepAndEmail(a.t());
        for (UUID u : List.of(a.owner(), a.participant())) {
            var rows = risk(a, u);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("title")).isEqualTo("SLA case breached its SLA");
        }
    }

    @Test
    void aNewStageVisitReArms() {
        var a = arrange("ra-rearm", true);
        UUID dave = user(a.t(), "dave@ra-rearm.test", COMPLETER);
        clock.advance(Duration.ofDays(2).plusHours(1));
        sla.sweepAndEmail(a.t());
        assertThat(risk(a, a.owner())).hasSize(1);

        UUID rid = fixture.runAsReturning(a.t(), () -> sla.firstRequirementId(a.caseId()));
        fixture.runAsUser(a.t(), dave, () -> requirements.satisfy(rid, null, null));   // enters stage two now
        clock.advance(Duration.ofDays(1).plusHours(1));   // 1 working day into stage two: 2 of 3 remain
        sla.sweepAndEmail(a.t());
        assertThat(risk(a, a.owner())).hasSize(1);
        clock.advance(Duration.ofDays(1));                // 2 days in: 1 remains
        sla.sweepAndEmail(a.t());
        var rows = risk(a, a.owner());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).get("tone")).isEqualTo("WARN");
    }

    @Test
    void aPausedClockIsNotAtRisk() {
        var a = arrange("ra-paused", false);
        fixture.runAs(a.t(), () -> cases.hold(a.caseId(), "waiting"));
        clock.advance(Duration.ofDays(4));
        sla.sweepAndEmail(a.t());
        assertThat(risk(a, a.owner())).isEmpty();
        assertThat(risk(a, a.participant())).isEmpty();
    }

    @Test
    void narrowScopedAudienceMembersDoNotBreakTheAlertForTheRest() {
        var a = arrange("ra-team", false);
        UUID outsider = user(a.t(), "out@ra-team.test", Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
        fixture.runAs(a.t(), () -> journey.addParticipant(a.t(), a.caseId(), outsider, RelationshipType.PARTICIPANT,
                ParticipantStatus.ACTIVE));
        UUID dept = user(a.t(), "dept@ra-team.test", Map.of(PermissionKeys.CASE_VIEW, Scope.DEPARTMENT));
        fixture.runAs(a.t(), () -> journey.addParticipant(a.t(), a.caseId(), dept, RelationshipType.PARTICIPANT,
                ParticipantStatus.ACTIVE));
        clock.advance(Duration.ofDays(2).plusHours(1));
        sla.sweepAndEmail(a.t());                         // must not throw for narrower-than-ALL recipients
        assertThat(risk(a, a.owner())).hasSize(1);
    }
}
