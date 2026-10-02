package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.provisioning.TenantProvisioningService;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class TenantBusinessCalendarTest extends PostgresTestBase {

    @Autowired BusinessCalendar calendar;
    @Autowired TenantFixture fixture;
    @Autowired TenantProvisioningService provisioning;
    @Autowired JdbcTemplate jdbc;   // onboarding_app, RLS-bound

    private static final LocalDate FRI = LocalDate.of(2026, 10, 2);

    private void addHoliday(UUID tenant) {
        fixture.runAs(tenant, () -> jdbc.update("""
                INSERT INTO business_holiday (id, tenant_id, holiday_date, name, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, '2026-10-05', 'Labour Day', now(), now())""", tenant));
    }

    @Test
    void theOnlyCalendarBeanIsTheTenantOne() {
        assertThat(calendar).isInstanceOf(TenantBusinessCalendar.class);
    }

    // TenantFixture.createTenant inserts the tenant row directly (no calendar row), so this
    // exercises the weekday-UTC fallback every fixture tenant in the suite depends on.
    @Test
    void aFixtureTenantWithNoCalendarRowFallsBackToWeekdayUtc() {
        UUID tenant = fixture.createTenant("cal-default");
        fixture.runAs(tenant, () -> {
            assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5));
            assertThat(calendar.name()).isEqualTo("Business calendar");
        });
    }

    @Test
    void holidaysAreReadPerTenant() {
        UUID a = fixture.createTenant("cal-a");
        UUID b = fixture.createTenant("cal-b");
        addHoliday(a);
        fixture.runAs(a, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6)));
        fixture.runAs(b, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5)));
    }

    @Test
    void theCacheNeverOutlivesATransaction() {
        UUID tenant = fixture.createTenant("cal-cache");
        fixture.runAs(tenant, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5)));
        addHoliday(tenant);
        fixture.runAs(tenant, () -> assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 6)));
    }

    @Test
    void provisioningSeedsACalendarAndAPolicy() {
        UUID tenant = provisioning.provision("cal-seeded", "Cal Seeded", "admin@cal-seeded.example", "Cal Admin");
        fixture.runAs(tenant, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM business_calendar", Integer.class)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sla_policy", Integer.class)).isOne();
            assertThat(calendar.plusBusinessDays(FRI, 1)).isEqualTo(LocalDate.of(2026, 10, 5));
        });
    }
}
