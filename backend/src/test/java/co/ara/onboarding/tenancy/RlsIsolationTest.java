package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RlsIsolationTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void rlsHidesRowsOfOtherTenants() {
        // DDL and grants as owner; everything asserted below runs as onboarding_app.
        ownerJdbc().execute("""
            CREATE TABLE IF NOT EXISTS rls_probe (
                id uuid PRIMARY KEY, tenant_id uuid NOT NULL, label text NOT NULL)
            """);
        ownerJdbc().execute("SELECT enable_tenant_rls('rls_probe')");
        ownerJdbc().execute("GRANT SELECT, INSERT ON rls_probe TO onboarding_app");

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
        jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'a-row')", UUID.randomUUID(), tenantA);

        jdbc.execute("SET app.tenant_id = '" + tenantB + "'");
        jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'b-row')", UUID.randomUUID(), tenantB);

        Integer visibleToB = jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class);
        assertThat(visibleToB).as("tenant B must see only its own row").isEqualTo(1);

        jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
        String label = jdbc.queryForObject("SELECT label FROM rls_probe", String.class);
        assertThat(label).isEqualTo("a-row");
    }

    @Test
    void unsetTenantContextSeesNothing() {
        ownerJdbc().execute("""
            CREATE TABLE IF NOT EXISTS rls_probe2 (
                id uuid PRIMARY KEY, tenant_id uuid NOT NULL)
            """);
        ownerJdbc().execute("SELECT enable_tenant_rls('rls_probe2')");
        ownerJdbc().execute("GRANT SELECT, INSERT ON rls_probe2 TO onboarding_app");

        UUID tenant = UUID.randomUUID();
        jdbc.execute("SET app.tenant_id = '" + tenant + "'");
        jdbc.update("INSERT INTO rls_probe2 VALUES (?, ?)", UUID.randomUUID(), tenant);

        jdbc.execute("RESET app.tenant_id");
        Integer visible = jdbc.queryForObject("SELECT count(*) FROM rls_probe2", Integer.class);
        assertThat(visible).as("no tenant context must reveal no rows").isZero();
    }
}
