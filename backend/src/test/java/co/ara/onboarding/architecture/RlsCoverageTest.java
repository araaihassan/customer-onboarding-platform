package co.ara.onboarding.architecture;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deny-by-default: every table in the public schema must be RLS-protected
 * unless it is on this explicitly reviewed allowlist (spec 11.2).
 * Adding an entry here is a deliberate act. Do not add one to make a test pass.
 */
class RlsCoverageTest extends PostgresTestBase {

    private static final Set<String> NOT_TENANT_SCOPED = Set.of(
            "tenant",                  // the tenant registry itself
            "platform_admin",          // vendor-side administration
            "permission",              // global catalog mirror
            "flyway_schema_history"
    );

    /**
     * Tables created at runtime by other tests against the shared container
     * (AppRoleTest's rls_probe_delete, RlsIsolationTest's rls_probe). They are
     * deliberately outside the tenancy model -- rls_probe_delete has no
     * tenant_id at all -- and whether they exist when this class runs depends
     * on test ordering. The rls_probe_* naming convention exists precisely so
     * this meta-test can skip them without an allowlist entry that would look
     * like a reviewed exemption for a real table.
     */
    private static boolean isTestProbe(String table) {
        return table.startsWith("rls_probe");
    }

    @Autowired JdbcTemplate jdbc;

    /**
     * relispartition = false excludes audit_event's partitions. They are
     * independently RLS-enabled by V5_1 as defence in depth, but the parent is
     * what the tenancy contract is expressed on, and a partition list that
     * grows monthly should not have to be enumerated here.
     */
    private List<String> baseTables() {
        return jdbc.queryForList("""
            SELECT c.relname
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relkind IN ('r', 'p')
              AND c.relispartition = false
            """, String.class);
    }

    @Test
    void everyTenantScopedTableHasForcedRlsAndAPolicy() {
        for (String table : baseTables()) {
            if (NOT_TENANT_SCOPED.contains(table) || isTestProbe(table)) continue;

            Boolean rlsEnabled = jdbc.queryForObject(
                    "SELECT relrowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Boolean rlsForced = jdbc.queryForObject(
                    "SELECT relforcerowsecurity FROM pg_class WHERE relname = ?", Boolean.class, table);
            Integer policies = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_policies WHERE tablename = ?", Integer.class, table);

            assertThat(rlsEnabled).as("%s must have RLS enabled", table).isTrue();
            assertThat(rlsForced).as("%s must have RLS FORCED", table).isTrue();
            assertThat(policies).as("%s must have a policy", table).isPositive();
        }
    }

    @Test
    void everyTenantScopedTableHasATenantIdColumn() {
        for (String table : baseTables()) {
            if (NOT_TENANT_SCOPED.contains(table) || isTestProbe(table)) continue;

            Integer hasColumn = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = ? AND column_name = 'tenant_id'
                """, Integer.class, table);
            assertThat(hasColumn).as("%s must have tenant_id", table).isEqualTo(1);
        }
    }

    /**
     * The meta-test is only worth having if it actually inspects tables. A
     * filter bug, a schema that failed to migrate, or an allowlist that grew
     * to cover everything would all leave the two tests above passing over an
     * empty loop -- green, and proving nothing. This pins the tables that must
     * be under the tenancy contract right now; Task 8 onward extends it.
     */
    @Test
    void actuallyInspectsTheTenantScopedTables() {
        List<String> inspected = baseTables().stream()
                .filter(t -> !NOT_TENANT_SCOPED.contains(t) && !isTestProbe(t))
                .toList();

        assertThat(inspected)
                .as("the RLS meta-test must not be passing over an empty table set")
                .contains("app_user", "department", "team", "team_member", "audit_event");
    }
}
