package co.ara.onboarding.platform;

import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Component
public class WeekdayBusinessCalendar implements BusinessCalendar {

    @Override
    public LocalDate plusBusinessDays(LocalDate from, int days) {
        // A weekend start anchors forward first, so a one-day milestone created on a
        // Saturday is not due before any working time has passed.
        LocalDate cursor = nextWorkingDay(from);
        for (int i = 0; i < days; i++) cursor = nextWorkingDay(cursor.plusDays(1));
        return cursor;
    }

    @Override
    public int businessDaysBetween(LocalDate from, LocalDate to) {
        if (!to.isAfter(from)) return 0;
        int days = 0;
        for (LocalDate cursor = from; cursor.isBefore(to); cursor = cursor.plusDays(1)) {
            if (isWorkingDay(cursor)) days++;
        }
        return days;
    }

    private LocalDate nextWorkingDay(LocalDate date) {
        LocalDate cursor = date;
        while (!isWorkingDay(cursor)) cursor = cursor.plusDays(1);
        return cursor;
    }

    private boolean isWorkingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
