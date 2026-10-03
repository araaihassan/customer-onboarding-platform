package co.ara.onboarding.sla;

import java.util.List;

/** The tenant's business calendar. {@code holidays} are managed through their own routes. */
public record BusinessCalendarView(String name, String timezone, List<Integer> workingDays,
                                   List<HolidayView> holidays) {}
