package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.EnumSet;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class CalendarRulesTest {

    private static final Set<DayOfWeek> MON_FRI =
            EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    // 2026-10-02 is a Friday.
    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);

    // Ported from the retired BusinessCalendarTest (WeekdayBusinessCalendar behaviour).
    private final CalendarRules weekdays = CalendarRules.weekdaysUtc();

    @Test
    void addingBusinessDaysSkipsWeekends() {
        assertThat(weekdays.plusBusinessDays(LocalDate.of(2026, 8, 21), 1)).isEqualTo(LocalDate.of(2026, 8, 24));
        assertThat(weekdays.plusBusinessDays(LocalDate.of(2026, 8, 21), 5)).isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    void addingZeroDaysReturnsTheSameDate() {
        assertThat(weekdays.plusBusinessDays(LocalDate.of(2026, 8, 21), 0)).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void aWeekendStartAnchorsOnTheNextWorkingDay() {
        assertThat(weekdays.plusBusinessDays(LocalDate.of(2026, 8, 22), 1)).isEqualTo(LocalDate.of(2026, 8, 25));
    }

    @Test
    void businessDaysBetweenExcludesWeekends() {
        assertThat(weekdays.businessDaysBetween(LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 28))).isEqualTo(5);
    }

    @Test
    void businessDaysBetweenIsZeroWhenTheRangeIsInverted() {
        assertThat(weekdays.businessDaysBetween(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 21))).isZero();
    }

    @Test
    void plusBusinessDaysSkipsHolidays() {
        var rules = new CalendarRules(ZoneOffset.UTC, MON_FRI, Set.of(LocalDate.of(2026, 10, 5))); // Monday holiday
        assertThat(rules.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6));
    }

    @Test
    void businessDaysBetweenSkipsHolidaysAndWeekends() {
        var rules = new CalendarRules(ZoneOffset.UTC, MON_FRI, Set.of(LocalDate.of(2026, 10, 5)));
        // [Fri, Wed) = Fri, (Sat, Sun, Mon-holiday skipped), Tue = 2
        assertThat(rules.businessDaysBetween(FRI, LocalDate.of(2026, 10, 7))).isEqualTo(2);
    }

    @Test
    void customWorkingDaysAreHonoured() {
        var sunThu = EnumSet.of(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY);
        var rules = new CalendarRules(ZoneOffset.UTC, sunThu, Set.of());
        assertThat(rules.isBusinessDay(FRI)).isFalse();
        assertThat(rules.isBusinessDay(LocalDate.of(2026, 10, 4))).isTrue(); // Sunday
    }

    @Test
    void businessDurationCountsFractionalWorkingTime() {
        var rules = CalendarRules.weekdaysUtc();
        Instant fri06 = Instant.parse("2026-10-02T06:00:00Z");
        Instant mon12 = Instant.parse("2026-10-05T12:00:00Z");
        // Fri 06:00 to 24:00 = 0.75, Sat/Sun 0, Mon 00:00 to 12:00 = 0.5
        assertThat(rules.businessDuration(fri06, mon12)).isCloseTo(1.25, within(1e-9));
    }

    @Test
    void businessDurationIsZeroForAnEmptyOrReversedInterval() {
        var rules = CalendarRules.weekdaysUtc();
        Instant t = Instant.parse("2026-10-02T06:00:00Z");
        assertThat(rules.businessDuration(t, t)).isZero();
        assertThat(rules.businessDuration(t, t.minusSeconds(60))).isZero();
    }

    @Test
    void businessDurationRespectsTheTenantZoneAcrossMidnight() {
        // Auckland is UTC+13 in October (NZDT).
        var auckland = ZoneId.of("Pacific/Auckland");
        var rules = new CalendarRules(auckland, MON_FRI, Set.of());
        // 2026-10-02T11:00Z = Sat 2026-10-03 00:00 Auckland. Sat to Sun in Auckland: no business time.
        Instant from = Instant.parse("2026-10-02T11:00:00Z");
        Instant to = Instant.parse("2026-10-04T11:00:00Z");
        assertThat(rules.businessDuration(from, to)).isZero();
        // The same instants are Friday 11:00Z to Sunday 11:00Z in UTC: 13/24 business days there.
        assertThat(CalendarRules.weekdaysUtc().businessDuration(from, to)).isCloseTo(13.0 / 24, within(1e-9));
    }

    @Test
    void todayIsTheDateInTheTenantZone() {
        var rules = new CalendarRules(ZoneId.of("Pacific/Auckland"), MON_FRI, Set.of());
        Clock clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC);
        assertThat(rules.today(clock)).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    void startOfDayIsMidnightInTheZone() {
        var rules = new CalendarRules(ZoneId.of("Pacific/Auckland"), MON_FRI, Set.of());
        assertThat(rules.startOfDay(LocalDate.of(2026, 10, 3))).isEqualTo(Instant.parse("2026-10-02T11:00:00Z"));
    }
}
