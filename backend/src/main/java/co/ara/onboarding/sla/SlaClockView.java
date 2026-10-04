package co.ara.onboarding.sla;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything the browser needs to render a clock, computed once on the server (spec §8,
 * STATE_AND_DATA L138: never `now - startedAt` in the browser). escalatedTo is null until the
 * clock itself has been escalated.
 */
public record SlaClockView(UUID clockId, UUID caseId, UUID stageId, int targetDays, double elapsedDays,
        double pausedDays, double remainingDays, SlaClockState state, boolean atRisk, boolean dueToday,
        PauseReason pauseReason, boolean pauseEligible, Instant startedAt, Instant stoppedAt,
        Instant breachedAt, EscalatedTo escalatedTo, String calendarName) {

    /** userId and name are null when route is ADMINISTRATORS. */
    public record EscalatedTo(EscalationRoute route, UUID userId, String name, Instant at) {}
}
