package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.CalendarRules;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;

/**
 * The tenant's configured business calendar (spec §3.3). Reads business_calendar and
 * business_holiday for the tenant bound to this thread, through the RLS-bound application
 * connection, and caches the result for the current transaction only -- never across
 * transactions, so a holiday added in one request is honoured by the next.
 *
 * A tenant with no business_calendar row (a test fixture that inserts the tenant directly)
 * falls back to Monday-Friday UTC, which is exactly what the retired WeekdayBusinessCalendar
 * did for everyone (holidays, if any, are still honoured).
 */
@Component
public class TenantBusinessCalendar implements BusinessCalendar {

    public static final String DEFAULT_NAME = "Business calendar";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TenantBusinessCalendar(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override public LocalDate plusBusinessDays(LocalDate from, int days) { return rules().rules().plusBusinessDays(from, days); }
    @Override public int businessDaysBetween(LocalDate from, LocalDate to) { return rules().rules().businessDaysBetween(from, to); }
    @Override public double businessDuration(Instant from, Instant to) { return rules().rules().businessDuration(from, to); }
    @Override public LocalDate today() { return rules().rules().today(clock); }
    @Override public LocalDate localDate(Instant instant) { return rules().rules().localDate(instant); }
    @Override public Instant startOfDay(LocalDate d) { return rules().rules().startOfDay(d); }
    @Override public String name() { return rules().name(); }

    private record Loaded(CalendarRules rules, String name) {}

    private Loaded rules() {
        UUID tenantId = TenantContext.getRequired();
        String key = TenantBusinessCalendar.class.getName() + ":" + tenantId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            Object cached = TransactionSynchronizationManager.getResource(key);
            if (cached instanceof Loaded l) return l;
            Loaded loaded = load(tenantId);
            TransactionSynchronizationManager.bindResource(key, loaded);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(key);
                }
            });
            return loaded;
        }
        return load(tenantId);
    }

    private Loaded load(UUID tenantId) {
        List<Loaded> rows = jdbc.query("""
                SELECT name, timezone, working_days FROM business_calendar WHERE tenant_id = ?""",
                (rs, i) -> {
                    // smallint[] arrives as Short[]; read as Object[] and convert.
                    Object[] days = (Object[]) rs.getArray("working_days").getArray();
                    EnumSet<DayOfWeek> working = EnumSet.noneOf(DayOfWeek.class);
                    for (Object d : days) working.add(DayOfWeek.of(((Number) d).intValue()));
                    return new Loaded(new CalendarRules(ZoneId.of(rs.getString("timezone")), working,
                            holidays(tenantId)), rs.getString("name"));
                }, tenantId);
        if (!rows.isEmpty()) return rows.get(0);
        CalendarRules d = CalendarRules.weekdaysUtc();
        return new Loaded(new CalendarRules(d.zone(), d.workingDays(), holidays(tenantId)), DEFAULT_NAME);
    }

    private Set<LocalDate> holidays(UUID tenantId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT holiday_date FROM business_holiday WHERE tenant_id = ?", LocalDate.class, tenantId));
    }
}
