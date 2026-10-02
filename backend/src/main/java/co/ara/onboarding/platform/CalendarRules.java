package co.ara.onboarding.platform;

import java.time.*;
import java.util.EnumSet;
import java.util.Set;

/**
 * The pure business-calendar maths every tenant calendar delegates to (spec §3.3, §1.2.13).
 * A business day counts as a whole 24-hour day in the calendar's zone; any other day counts
 * zero. No working hours -- nothing in the PRD or QA Q8 asks for them.
 */
public record CalendarRules(ZoneId zone, Set<DayOfWeek> workingDays, Set<LocalDate> holidays) {

    public CalendarRules {
        workingDays = workingDays.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : EnumSet.copyOf(workingDays);
        holidays = Set.copyOf(holidays);
        if (workingDays.isEmpty()) {
            throw new IllegalArgumentException("A calendar needs at least one working day");
        }
    }

    public static CalendarRules weekdaysUtc() {
        return new CalendarRules(ZoneOffset.UTC,
                EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), Set.of());
    }

    public boolean isBusinessDay(LocalDate d) {
        return workingDays.contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    /** Next business day on or after {@code from}, then {@code days} more. Matches the retired weekday calendar. */
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        LocalDate cursor = nextBusinessDay(from);
        for (int i = 0; i < days; i++) cursor = nextBusinessDay(cursor.plusDays(1));
        return cursor;
    }

    /** Business days in {@code [from, to)}; 0 when {@code to} is not after {@code from}. */
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        int count = 0;
        for (LocalDate d = from; d.isBefore(to); d = d.plusDays(1)) if (isBusinessDay(d)) count++;
        return count;
    }

    /** Fractional business days in {@code [from, to)}, walking local days in this calendar's zone. */
    public double businessDuration(Instant from, Instant to) {
        if (!to.isAfter(from)) return 0;
        double total = 0;
        LocalDate day = localDate(from);
        LocalDate last = localDate(to);
        while (!day.isAfter(last)) {
            if (isBusinessDay(day)) {
                Instant dayStart = day.atStartOfDay(zone).toInstant();
                Instant dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant();
                Instant a = from.isAfter(dayStart) ? from : dayStart;
                Instant b = to.isBefore(dayEnd) ? to : dayEnd;
                if (b.isAfter(a)) {
                    double dayLength = Duration.between(dayStart, dayEnd).toMillis();
                    total += Duration.between(a, b).toMillis() / dayLength;
                }
            }
            day = day.plusDays(1);
        }
        return total;
    }

    public LocalDate today(Clock clock) { return LocalDate.ofInstant(clock.instant(), zone); }

    public LocalDate localDate(Instant instant) { return LocalDate.ofInstant(instant, zone); }

    private LocalDate nextBusinessDay(LocalDate d) {
        LocalDate cursor = d;
        while (!isBusinessDay(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }
}
