package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessCalendarTest {

    private final BusinessCalendar calendar = new WeekdayBusinessCalendar();

    @Test
    void addingBusinessDaysSkipsWeekends() {
        // Friday 2026-08-21 + 1 business day = Monday 2026-08-24
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 1))
                .isEqualTo(LocalDate.of(2026, 8, 24));
        // Friday + 5 = the following Friday
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 5))
                .isEqualTo(LocalDate.of(2026, 8, 28));
    }

    @Test
    void addingZeroDaysReturnsTheSameDate() {
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 21), 0))
                .isEqualTo(LocalDate.of(2026, 8, 21));
    }

    /**
     * A milestone planned from a Saturday is not a hypothetical: a case created at the
     * weekend starts its first stage then. Anchoring on the following Monday is what
     * keeps a one-day milestone from being due before any working time has passed.
     */
    @Test
    void aWeekendStartAnchorsOnTheNextWorkingDay() {
        assertThat(calendar.plusBusinessDays(LocalDate.of(2026, 8, 22), 1))   // Saturday
                .isEqualTo(LocalDate.of(2026, 8, 25));                        // Tue after Mon
    }

    @Test
    void businessDaysBetweenExcludesWeekends() {
        assertThat(calendar.businessDaysBetween(
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 28))).isEqualTo(5);
    }

    @Test
    void businessDaysBetweenIsZeroWhenTheRangeIsInverted() {
        assertThat(calendar.businessDaysBetween(
                LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 21))).isZero();
    }
}
