package co.ara.onboarding.sla;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Administration of the tenant's business calendar, its holidays and its SLA policy (spec 8, 4.7).
 * calendar.manage is ALL-only with no resource type, so there is no scope predicate to apply; the
 * tables are tenant-bound by RLS on the application connection. TenantBusinessCalendar caches per
 * transaction only, so an edit here is honoured by the very next request.
 */
@Service
public class CalendarAdminService {

    private static final double MAX_AT_RISK_DAYS = 999.9;   // numeric(4,1)

    private final JdbcTemplate jdbc;
    private final AuditRecorder audit;

    public CalendarAdminService(JdbcTemplate jdbc, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    private record CalendarRow(UUID id, String name, String timezone, List<Integer> days) {}

    private record PolicyRow(UUID id, SlaPolicy policy) {}

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional(readOnly = true)
    public BusinessCalendarView calendar() {
        CalendarRow row = readCalendar().orElse(
                new CalendarRow(null, "Business calendar", "UTC", List.of(1, 2, 3, 4, 5)));
        return new BusinessCalendarView(row.name(), row.timezone(), row.days(), holidays());
    }

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional
    public BusinessCalendarView updateCalendar(UpdateBusinessCalendarRequest r) {
        String name = r.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name must not be blank");
        String tz = r.timezone().trim();
        // getAvailableZoneIds rejects offsets ("UTC+3"), lower-case and legacy spellings that ZoneId.of
        // would accept; one bad stored value would make ZoneId.of throw on every calendar call.
        try {
            if (!ZoneId.getAvailableZoneIds().contains(tz)) throw new IllegalArgumentException();
            ZoneId.of(tz);
        } catch (IllegalArgumentException | DateTimeException e) {
            throw new IllegalArgumentException("Unknown timezone: " + r.timezone());
        }
        TreeSet<Integer> days = new TreeSet<>();
        for (Integer d : r.workingDays()) {
            if (d == null || d < 1 || d > 7) {
                throw new IllegalArgumentException("Working days must be 1 (Monday) to 7 (Sunday)");
            }
            days.add(d);
        }
        List<Integer> dayList = List.copyOf(days);
        String literal = days.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}"));
        Map<String, Object> payload = Map.of("name", name, "timezone", tz, "workingDays", dayList);

        Optional<CalendarRow> existing = readCalendar();
        if (existing.isPresent()) {
            CalendarRow e = existing.get();
            if (e.name().equals(name) && e.timezone().equals(tz) && e.days().equals(dayList)) {
                return calendar();   // nothing changed: no write, no audit
            }
            audit.record(AuditActions.CALENDAR_UPDATED, "business_calendar", e.id(),
                    "Updated the business calendar", payload);
            jdbc.update("UPDATE business_calendar SET name = ?, timezone = ?, working_days = ?::smallint[], "
                    + "updated_at = now() WHERE id = ?", name, tz, literal, e.id());
        } else {
            UUID id = Uuid7.generate();
            audit.record(AuditActions.CALENDAR_UPDATED, "business_calendar", id,
                    "Updated the business calendar", payload);
            jdbc.update("INSERT INTO business_calendar (id, tenant_id, name, timezone, working_days, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?::smallint[], now(), now())", id, TenantContext.getRequired(), name, tz, literal);
        }
        return new BusinessCalendarView(name, tz, dayList, holidays());
    }

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional
    public HolidayView addHoliday(CreateHolidayRequest r) {
        String name = r.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Name must not be blank");
        UUID id = Uuid7.generate();
        List<UUID> inserted = jdbc.queryForList("""
                INSERT INTO business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                ON CONFLICT ON CONSTRAINT business_holiday_tenant_date_uq DO NOTHING RETURNING id""",
                UUID.class, id, TenantContext.getRequired(), r.date(), name);
        if (inserted.isEmpty()) throw new IllegalStateException("A holiday already exists on " + r.date());
        audit.record(AuditActions.CALENDAR_HOLIDAY_ADDED, "business_holiday", id,
                "Added holiday " + name + " on " + r.date(), Map.of("date", r.date().toString(), "name", name));
        return new HolidayView(id, r.date(), name);
    }

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional
    public void removeHoliday(UUID id) {
        // RLS hides another tenant's row, so a foreign id is indistinguishable from an unknown one.
        List<HolidayView> found = jdbc.query("SELECT id, holiday_date, name FROM business_holiday WHERE id = ?",
                (rs, i) -> new HolidayView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getString(3)), id);
        if (found.isEmpty()) throw new NoSuchElementException("Not found");
        HolidayView h = found.get(0);
        // Recorded before the DELETE: the audit row is the only record the holiday ever existed.
        audit.record(AuditActions.CALENDAR_HOLIDAY_REMOVED, "business_holiday", id,
                "Removed holiday " + h.name() + " on " + h.date(),
                Map.of("date", h.date().toString(), "name", h.name()));
        jdbc.update("DELETE FROM business_holiday WHERE id = ?", id);
    }

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional(readOnly = true)
    public SlaPolicyView policy() {
        SlaPolicy p = readPolicy().map(PolicyRow::policy).orElse(SlaPolicy.defaults());
        return new SlaPolicyView(p.atRiskDays(), p.escalateAfterOverdueDays());
    }

    @RequirePermission(PermissionKeys.CALENDAR_MANAGE)
    @Transactional
    public SlaPolicyView updatePolicy(UpdateSlaPolicyRequest r) {
        double atRisk = r.atRiskDays();
        int esc = r.escalateAfterOverdueDays();
        if (atRisk < 0 || atRisk > MAX_AT_RISK_DAYS || Math.abs(atRisk * 10 - Math.rint(atRisk * 10)) > 1e-9) {
            throw new IllegalArgumentException("atRiskDays must be between 0 and 999.9, in steps of 0.1");
        }
        if (esc < 1) throw new IllegalArgumentException("escalateAfterOverdueDays must be at least 1");
        Map<String, Object> payload = Map.of("atRiskDays", atRisk, "escalateAfterOverdueDays", esc);
        Optional<PolicyRow> existing = readPolicy();
        if (existing.isPresent()) {
            SlaPolicy p = existing.get().policy();
            if (p.atRiskDays() == atRisk && p.escalateAfterOverdueDays() == esc) return policy();
            audit.record(AuditActions.SLA_POLICY_UPDATED, "sla_policy", existing.get().id(),
                    "Updated the SLA policy", payload);
            jdbc.update("UPDATE sla_policy SET at_risk_days = ?, escalate_after_overdue_days = ?, "
                    + "updated_at = now() WHERE id = ?", atRisk, esc, existing.get().id());
        } else {
            UUID id = Uuid7.generate();
            audit.record(AuditActions.SLA_POLICY_UPDATED, "sla_policy", id, "Updated the SLA policy", payload);
            jdbc.update("INSERT INTO sla_policy (id, tenant_id, at_risk_days, escalate_after_overdue_days, "
                    + "created_at, updated_at) VALUES (?, ?, ?, ?, now(), now())",
                    id, TenantContext.getRequired(), atRisk, esc);
        }
        return new SlaPolicyView(atRisk, esc);
    }

    private Optional<PolicyRow> readPolicy() {
        return jdbc.query("SELECT id, at_risk_days, escalate_after_overdue_days FROM sla_policy",
                (rs, i) -> new PolicyRow(rs.getObject(1, UUID.class),
                        new SlaPolicy(rs.getDouble(2), rs.getInt(3)))).stream().findFirst();
    }

    private Optional<CalendarRow> readCalendar() {
        return jdbc.query("SELECT id, name, timezone, working_days FROM business_calendar", (rs, i) -> {
            List<Integer> days = new ArrayList<>();
            for (Object d : (Object[]) rs.getArray("working_days").getArray()) days.add(((Number) d).intValue());
            Collections.sort(days);
            return new CalendarRow(rs.getObject("id", UUID.class), rs.getString("name"),
                    rs.getString("timezone"), List.copyOf(days));
        }).stream().findFirst();
    }

    private List<HolidayView> holidays() {
        return jdbc.query("SELECT id, holiday_date, name FROM business_holiday ORDER BY holiday_date",
                (rs, i) -> new HolidayView(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getString(3)));
    }
}
