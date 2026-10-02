package co.ara.onboarding.platform;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Business-day arithmetic for the current tenant (spec §3.3). The only implementation is
 * tenancy.TenantBusinessCalendar, which reads the bound tenant's calendar; platform names no
 * domain type, so the interface lives here and the implementation there.
 */
public interface BusinessCalendar {
    LocalDate plusBusinessDays(LocalDate from, int days);
    int businessDaysBetween(LocalDate from, LocalDate to);
    /** Fractional business days in [from, to). */
    double businessDuration(Instant from, Instant to);
    /** Today's date in the tenant's timezone, from the injected Clock. */
    LocalDate today();
    /** The tenant-zone date of an instant. */
    LocalDate localDate(Instant instant);
    /** The calendar's display name, e.g. for the war room eyebrow. */
    String name();
}
