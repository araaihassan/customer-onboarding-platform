package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.CalendarRules;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class SlaClockReaderTest {

    /** Weekdays-UTC calendar whose today() follows the supplied clock. */
    static BusinessCalendar calendar(Clock clock) { return calendar(clock, CalendarRules.weekdaysUtc()); }

    static BusinessCalendar calendar(Clock clock, CalendarRules r) {
        return new BusinessCalendar() {
            public LocalDate plusBusinessDays(LocalDate f, int d) { return r.plusBusinessDays(f, d); }
            public int businessDaysBetween(LocalDate f, LocalDate t) { return r.businessDaysBetween(f, t); }
            public double businessDuration(Instant f, Instant t) { return r.businessDuration(f, t); }
            public LocalDate today() { return r.today(clock); }
            public LocalDate localDate(Instant i) { return r.localDate(i); }
            public Instant startOfDay(LocalDate d) { return r.startOfDay(d); }
            public ZoneId zone() { return r.zone(); }
            public String name() { return "Test calendar"; }
        };
    }

    // Monday 2026-10-05 09:00Z
    static final Instant MON_09 = Instant.parse("2026-10-05T09:00:00Z");

    static SlaClock clock(int targetDays, boolean eligible) {
        SlaClock c = new SlaClock();
        c.setId(UUID.randomUUID());
        c.setTargetDays(targetDays);
        c.setPauseEligible(eligible);
        c.setStartedAt(MON_09);
        return c;
    }

    static SlaPause pause(PauseReason reason, String from, String to) {
        SlaPause p = new SlaPause();
        p.setReason(reason);
        p.setStartedAt(Instant.parse(from));
        p.setEndedAt(to == null ? null : Instant.parse(to));
        return p;
    }

    static SlaClockReader reader(Instant now) {
        return new SlaClockReader(calendar(Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void elapsedIsBusinessTimeSinceStart() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z"); // Tue 09:00
        assertThat(reader(now).elapsed(clock(3, true), List.of(), now)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void overlappingPausesCountOnce() {
        // Review Focus 1. Hold Mon 12:00→Tue 12:00 and a request Mon 18:00→Tue 18:00 overlap;
        // their union is Mon 12:00→Tue 18:00 = 1.25 business days paused of 2.0 elapsed.
        Instant now = Instant.parse("2026-10-07T09:00:00Z"); // Wed 09:00
        var pauses = List.of(
                pause(PauseReason.CASE_HOLD, "2026-10-05T12:00:00Z", "2026-10-06T12:00:00Z"),
                pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T18:00:00Z", "2026-10-06T18:00:00Z"));
        var r = reader(now);
        assertThat(r.paused(clock(5, true), pauses, now)).isCloseTo(1.25, within(1e-9));
        assertThat(r.elapsed(clock(5, true), pauses, now)).isCloseTo(0.75, within(1e-9));
    }

    @Test
    void anOpenPauseRunsToNowAndMakesTheClockPaused() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T21:00:00Z", null));
        var v = reader(now).view(clock(3, true), pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.PAUSED);
        assertThat(v.pauseReason()).isEqualTo(PauseReason.CASE_HOLD);
        assertThat(v.elapsedDays()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void theEarliestOpenPauseIsTheReasonShown() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(
                pause(PauseReason.CASE_HOLD, "2026-10-05T20:00:00Z", null),
                pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T10:00:00Z", null));
        assertThat(reader(now).view(clock(3, true), pauses, now, SlaPolicy.defaults(), null).pauseReason())
                .isEqualTo(PauseReason.OPEN_DOCUMENT_REQUEST);
    }

    @Test
    void reachingTheTargetIsBreached() {
        Instant now = Instant.parse("2026-10-07T09:00:00Z"); // 2.0 elapsed
        var v = reader(now).view(clock(2, true), List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.BREACHED);
        assertThat(v.remainingDays()).isCloseTo(0.0, within(1e-9));
        assertThat(v.atRisk()).isFalse();
    }

    @Test
    void atRiskWhenRemainingIsWithinThePolicy() {
        Instant now = Instant.parse("2026-10-06T21:00:00Z"); // 1.5 elapsed of 2 → 0.5 left
        var v = reader(now).view(clock(2, true), List.of(), now, new SlaPolicy(1.0, 1), null);
        assertThat(v.atRisk()).isTrue();
        assertThat(v.remainingDays()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void dueTodayWhenTheRemainingTimeRunsOutBeforeMidnight() {
        Instant now = Instant.parse("2026-10-06T21:00:00Z"); // 0.5 left, only 0.125 of Tuesday left
        assertThat(reader(now).view(clock(2, true), List.of(), now, SlaPolicy.defaults(), null).dueToday()).isFalse();
        Instant later = Instant.parse("2026-10-07T06:00:00Z"); // Wed 06:00: 0.25 left, 0.75 of Wednesday left
        assertThat(reader(later).view(clock(2, true), List.of(), later, SlaPolicy.defaults(), null).dueToday()).isTrue();
    }

    @Test
    void aStoppedClockReportsItsOutcomeAndStopsCounting() {
        SlaClock c = clock(3, true);
        c.setStoppedAt(Instant.parse("2026-10-06T09:00:00Z"));
        c.setOutcome(SlaClockOutcome.MET);
        Instant now = Instant.parse("2026-10-20T09:00:00Z");
        var v = reader(now).view(c, List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.MET);
        assertThat(v.elapsedDays()).isCloseTo(1.0, within(1e-9));
        assertThat(v.atRisk()).isFalse();
        assertThat(v.dueToday()).isFalse();
    }

    @Test
    void aBreachStampOutranksTheArithmetic() {
        // Stamped breached by the sweep, then a back-dated pause shrank elapsed below target: still BREACHED.
        SlaClock c = clock(2, true);
        c.setBreachedAt(Instant.parse("2026-10-07T09:00:00Z"));
        Instant now = Instant.parse("2026-10-07T10:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T09:00:00Z", "2026-10-06T09:00:00Z"));
        assertThat(reader(now).view(c, pauses, now, SlaPolicy.defaults(), null).state()).isEqualTo(SlaClockState.BREACHED);
    }

    @Test
    void dueTodayUsesTheTenantZoneNotUtc() {
        // Review Focus 2. Auckland (UTC+13 in October). Started Wed 06:00 NZ; now Wed 20:00 NZ
        // (= Wed 07:00Z): 0.583 elapsed of 1, 0.417 left but only 4h (0.167) of the NZ day left.
        // Read in UTC the same instant has 17h of Wednesday left and would wrongly say due today.
        var nz = new CalendarRules(ZoneId.of("Pacific/Auckland"), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), java.util.Set.of());
        Instant now = Instant.parse("2026-10-07T07:00:00Z");
        SlaClock c = clock(1, true);
        c.setStartedAt(Instant.parse("2026-10-06T17:00:00Z"));
        var r = new SlaClockReader(calendar(Clock.fixed(now, ZoneOffset.UTC), nz));
        var v = r.view(c, List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.elapsedDays()).isCloseTo(14.0 / 24, within(1e-9));
        assertThat(v.dueToday()).isFalse();
        Instant later = Instant.parse("2026-10-07T10:00:00Z"); // Wed 23:00 NZ: 0.29 left, 1h of the day left
        assertThat(new SlaClockReader(calendar(Clock.fixed(later, ZoneOffset.UTC), nz))
                .view(c, List.of(), later, SlaPolicy.defaults(), null).dueToday()).isFalse();
    }

    @Test
    void aPausedClockDueTodayIsDueToday() {
        // Started Mon 09:00, paused since Mon 21:00 (0.5 elapsed, frozen); target 1 -> 0.5 left; Tue 06:00
        // leaves 0.75 of Tuesday. Spec 5 rule 4: paused clocks are not excluded.
        Instant now = Instant.parse("2026-10-06T06:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T21:00:00Z", null));
        var v = reader(now).view(clock(1, true), pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.PAUSED);
        assertThat(v.dueToday()).isTrue();
    }

    @Test
    void aBreachedClockIsNeverDueToday() {
        Instant now = Instant.parse("2026-10-07T09:00:00Z");
        assertThat(reader(now).view(clock(2, true), List.of(), now, SlaPolicy.defaults(), null).dueToday()).isFalse();
    }

    @Test
    void aucklandClockDueLaterTodayIsDueToday() {
        var nz = new CalendarRules(ZoneId.of("Pacific/Auckland"), EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), java.util.Set.of());
        SlaClock c = clock(1, true);
        c.setStartedAt(Instant.parse("2026-10-05T23:00:00Z")); // Tue 12:00 NZ
        Instant now = Instant.parse("2026-10-06T21:00:00Z");   // Wed 10:00 NZ: 22h elapsed, 2h remaining, 14h left in the day
        var v = new SlaClockReader(calendar(Clock.fixed(now, ZoneOffset.UTC), nz))
                .view(c, List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.dueToday()).isTrue();
    }

    @Test
    void atRiskWhileStillPaused() {
        Instant now = Instant.parse("2026-10-06T06:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T21:00:00Z", null));
        assertThat(reader(now).view(clock(1, true), pauses, now, SlaPolicy.defaults(), null).atRisk()).isTrue();
    }

    @Test
    void aPauseStartingBeforeTheClockIsClipped() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-04T09:00:00Z", "2026-10-05T21:00:00Z"));
        assertThat(reader(now).paused(clock(3, true), pauses, now)).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void aPauseEntirelyAfterStopIsDropped() {
        SlaClock c = clock(3, true);
        c.setStoppedAt(Instant.parse("2026-10-06T09:00:00Z"));
        c.setOutcome(SlaClockOutcome.MET);
        Instant now = Instant.parse("2026-10-09T09:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-07T09:00:00Z", null));
        var v = reader(now).view(c, pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.pausedDays()).isCloseTo(0.0, within(1e-9));
        assertThat(v.elapsedDays()).isCloseTo(1.0, within(1e-9));
        assertThat(v.pauseReason()).isNull();
    }

    @Test
    void aNestedPauseCountsOnce() {
        Instant now = Instant.parse("2026-10-07T09:00:00Z");
        var pauses = List.of(
                pause(PauseReason.CASE_HOLD, "2026-10-05T12:00:00Z", "2026-10-06T12:00:00Z"),
                pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T18:00:00Z", "2026-10-06T06:00:00Z"));
        assertThat(reader(now).paused(clock(5, true), pauses, now)).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void exactlyAtTargetIsBreachedEvenWithNonRoundMilliseconds() {
        SlaClock c = clock(3, true);
        Instant start = Instant.parse("2026-10-05T09:17:23.137Z");
        c.setStartedAt(start);
        Instant now = Instant.parse("2026-10-08T09:17:23.137Z"); // exactly 3 business days later
        var v = reader(now).view(c, List.of(), now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.BREACHED);
        assertThat(v.remainingDays()).isEqualTo(0.0);
    }

    @Test
    void anOpenDocumentRequestPauseIsIgnoredWhenTheClockIsNotPauseEligible() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(pause(PauseReason.OPEN_DOCUMENT_REQUEST, "2026-10-05T21:00:00Z", null));
        var v = reader(now).view(clock(3, false), pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.RUNNING);
        assertThat(v.pausedDays()).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void aCaseHoldStillPausesANonPauseEligibleClock() {
        Instant now = Instant.parse("2026-10-06T09:00:00Z");
        var pauses = List.of(pause(PauseReason.CASE_HOLD, "2026-10-05T21:00:00Z", null));
        var v = reader(now).view(clock(3, false), pauses, now, SlaPolicy.defaults(), null);
        assertThat(v.state()).isEqualTo(SlaClockState.PAUSED);
        assertThat(v.pauseReason()).isEqualTo(PauseReason.CASE_HOLD);
        assertThat(v.pausedDays()).isCloseTo(0.5, within(1e-9));
    }

    /** Stop-time outcome and the live view share one threshold: within EPS of the target is exhausted. */
    @Test
    void exhaustionUsesTheSameEpsilonAsTheLiveView() {
        SlaClockReader r = reader(MON_09);
        assertThat(r.exhausted(3 - 1e-10, 3)).isTrue();
        assertThat(r.exhausted(3.0, 3)).isTrue();
        assertThat(r.exhausted(3 - 1e-6, 3)).isFalse();
    }
}
