package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;

/**
 * The one computation of a clock's numbers (spec §5, invariant 4). Elapsed is business time from
 * start to stop-or-now minus the business time of the UNION of the pause intervals, so overlapping
 * hold and document-request pauses count once (rule 2).
 */
@Component
public class SlaClockReader {

    private final BusinessCalendar calendar;

    public SlaClockReader(BusinessCalendar calendar) { this.calendar = calendar; }

    public double elapsed(SlaClock clock, List<SlaPause> pauses, Instant now) {
        Instant end = end(clock, now);
        return Math.max(0, calendar.businessDuration(clock.getStartedAt(), end) - paused(clock, pauses, now));
    }

    public double paused(SlaClock clock, List<SlaPause> pauses, Instant now) {
        Instant end = end(clock, now);
        double total = 0;
        for (Instant[] interval : union(pauses, clock.getStartedAt(), end)) {
            total += calendar.businessDuration(interval[0], interval[1]);
        }
        return total;
    }

    public SlaClockView view(SlaClock clock, List<SlaPause> pauses, Instant now, SlaPolicy policy,
                             SlaClockView.EscalatedTo escalatedTo) {
        double elapsed = elapsed(clock, pauses, now);
        double paused = paused(clock, pauses, now);
        double remaining = Math.max(0, clock.getTargetDays() - elapsed);
        Optional<SlaPause> open = clock.getStoppedAt() == null
                ? pauses.stream().filter(p -> p.getEndedAt() == null).min(Comparator.comparing(SlaPause::getStartedAt))
                : Optional.empty();

        SlaClockState state;
        if (clock.getStoppedAt() != null) {
            state = clock.getOutcome() == SlaClockOutcome.BREACHED ? SlaClockState.BREACHED : SlaClockState.MET;
        } else if (clock.getBreachedAt() != null || elapsed >= clock.getTargetDays()) {
            state = SlaClockState.BREACHED;
        } else if (open.isPresent()) {
            state = SlaClockState.PAUSED;
        } else {
            state = SlaClockState.RUNNING;
        }

        boolean live = clock.getStoppedAt() == null && state != SlaClockState.BREACHED;
        boolean atRisk = live && remaining <= policy.atRiskDays();
        boolean dueToday = state == SlaClockState.RUNNING && remaining <= calendar.businessDuration(now,
                calendar.startOfDay(calendar.localDate(now).plusDays(1)));

        return new SlaClockView(clock.getId(), clock.getCaseId(), clock.getStageId(), clock.getTargetDays(),
                elapsed, paused, remaining, state, atRisk, dueToday,
                open.map(SlaPause::getReason).orElse(null), clock.isPauseEligible(),
                clock.getStartedAt(), clock.getStoppedAt(), clock.getBreachedAt(), escalatedTo, calendar.name());
    }

    private static Instant end(SlaClock clock, Instant now) {
        return clock.getStoppedAt() != null ? clock.getStoppedAt() : now;
    }

    /** Pause intervals clipped to [start, end] and merged where they overlap. */
    private static List<Instant[]> union(List<SlaPause> pauses, Instant start, Instant end) {
        List<Instant[]> clipped = new ArrayList<>();
        for (SlaPause p : pauses) {
            Instant a = p.getStartedAt().isBefore(start) ? start : p.getStartedAt();
            Instant b = p.getEndedAt() == null || p.getEndedAt().isAfter(end) ? end : p.getEndedAt();
            if (b.isAfter(a)) clipped.add(new Instant[]{a, b});
        }
        clipped.sort(Comparator.comparing(i -> i[0]));
        List<Instant[]> merged = new ArrayList<>();
        for (Instant[] i : clipped) {
            if (!merged.isEmpty() && !i[0].isAfter(merged.get(merged.size() - 1)[1])) {
                Instant[] last = merged.get(merged.size() - 1);
                if (i[1].isAfter(last[1])) last[1] = i[1];
            } else {
                merged.add(new Instant[]{i[0], i[1]});
            }
        }
        return merged;
    }
}
