package co.ara.onboarding.tenancy;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RlsIsolationTest extends PostgresTestBase {

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

        // The whole SET -> insert -> SET -> insert -> assert sequence runs over
        // one held connection: session GUCs like app.tenant_id only survive
        // across statements issued on the same physical connection, and a
        // pooled JdbcTemplate gives no guarantee that two calls land on the
        // same one. See withHeldConnection's javadoc in PostgresTestBase.
        withAppConnection(jdbc -> {
            jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
            jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'a-row')", UUID.randomUUID(), tenantA);

            jdbc.execute("SET app.tenant_id = '" + tenantB + "'");
            jdbc.update("INSERT INTO rls_probe VALUES (?, ?, 'b-row')", UUID.randomUUID(), tenantB);

            Integer visibleToB = jdbc.queryForObject("SELECT count(*) FROM rls_probe", Integer.class);
            assertThat(visibleToB).as("tenant B must see only its own row").isEqualTo(1);

            jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
            String label = jdbc.queryForObject("SELECT label FROM rls_probe", String.class);
            assertThat(label).isEqualTo("a-row");
        });
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

        // SET, the insert that depends on it, RESET, and the assertion that
        // depends on THAT must share one physical connection -- see
        // withHeldConnection's javadoc in PostgresTestBase.
        withAppConnection(jdbc -> {
            jdbc.execute("SET app.tenant_id = '" + tenant + "'");
            jdbc.update("INSERT INTO rls_probe2 VALUES (?, ?)", UUID.randomUUID(), tenant);

            jdbc.execute("RESET app.tenant_id");
            Integer visible = jdbc.queryForObject("SELECT count(*) FROM rls_probe2", Integer.class);
            assertThat(visible).as("no tenant context must reveal no rows").isZero();
        });
    }

    @Test
    void forceRowLevelSecurityAppliesToTableOwner() {
        // FORCE ROW LEVEL SECURITY only changes anything for the table's
        // OWNER -- it never applies to a superuser, who bypasses row
        // security unconditionally regardless of FORCE (PostgreSQL docs).
        // The container's owner role (ownerJdbc() / withOwnerConnection) IS
        // that superuser -- see the comment on flywayProperties() in
        // PostgresTestBase -- so no assertion made through it could ever
        // distinguish FORCE from plain ENABLE; it bypasses both the same way.
        //
        // To make FORCE observable at all, this test reassigns ownership of
        // a throwaway probe table to onboarding_app, which AppRoleTest
        // already proves is neither superuser nor BYPASSRLS, and then
        // asserts through onboarding_app's OWN visibility of a table IT
        // owns. That is the one deliberate exception to "never assert RLS
        // or privilege behaviour through the owning role" flagged in
        // ownerJdbc()'s and withOwnerConnection's javadoc: here the owning
        // role's own visibility, under FORCE, is exactly the thing under
        // test. Do not rewrite this against the container's actual owner
        // role/ownerJdbc() -- that role's superuser status makes the
        // assertion meaningless (it would pass identically whether or not
        // the FORCE line exists in enable_tenant_rls).
        withOwnerConnection(owner -> {
            owner.execute("""
                CREATE TABLE IF NOT EXISTS rls_probe_force (
                    id uuid PRIMARY KEY, tenant_id uuid NOT NULL)
                """);
            owner.execute("ALTER TABLE rls_probe_force OWNER TO onboarding_app");
            owner.execute("SELECT enable_tenant_rls('rls_probe_force')");
        });

        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        withAppConnection(jdbc -> {
            jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
            jdbc.update("INSERT INTO rls_probe_force VALUES (?, ?)", UUID.randomUUID(), tenantA);

            jdbc.execute("SET app.tenant_id = '" + tenantB + "'");
            jdbc.update("INSERT INTO rls_probe_force VALUES (?, ?)", UUID.randomUUID(), tenantB);

            jdbc.execute("SET app.tenant_id = '" + tenantA + "'");
            Integer visibleToOwner = jdbc.queryForObject(
                    "SELECT count(*) FROM rls_probe_force", Integer.class);
            assertThat(visibleToOwner)
                    .as("FORCE ROW LEVEL SECURITY must constrain the table owner to its own tenant's rows")
                    .isEqualTo(1);
        });
    }
}
