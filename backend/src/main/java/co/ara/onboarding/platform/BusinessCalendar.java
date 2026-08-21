package co.ara.onboarding.platform;

import java.time.LocalDate;

/**
 * Business-day arithmetic, behind an interface because Q8 says "business days, using
 * the configured business calendar" and configuration does not exist yet: there is no
 * tenant_setting table and tenant.settings.view/edit are catalogued permissions with
 * nothing behind them.
 *
 * WeekdayBusinessCalendar is Monday-to-Friday with no holidays. Sub-project 6 replaces
 * it with tenant-configured working days and a holiday table when it builds the SLA
 * machinery that needs them; until then the honest description of this is "a hardcoded
 * weekend rule", which is why it is stated in CLAUDE.md rather than implied to be
 * configurable.
 */
public interface BusinessCalendar {
    LocalDate plusBusinessDays(LocalDate from, int days);
    int businessDaysBetween(LocalDate from, LocalDate to);
}
