package co.ara.onboarding.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppRoleTest extends PostgresTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationConnectsAsNonSuperuserWithoutBypassRls() {
        String currentUser = jdbc.queryForObject("SELECT current_user", String.class);
        assertThat(currentUser).isEqualTo("onboarding_app");

        Boolean superuser = jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class);
        Boolean bypassRls = jdbc.queryForObject(
                "SELECT rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class);

        assertThat(superuser).as("app role must not be superuser").isFalse();
        assertThat(bypassRls).as("app role must not have BYPASSRLS").isFalse();
    }

    @Test
    void applicationRoleCannotDeleteFromTenantRegistry() {
        // Spring's JdbcTemplate wraps the driver's SQLException in a
        // BadSqlGrammarException whose own getMessage() is a generic
        // "StatementCallback; bad SQL grammar [...]" -- the driver's actual
        // "permission denied for table tenant" text is on the cause, not the
        // top-level message, so the assertion checks the full stack trace text
        // (which includes "Caused by:") rather than hasMessageContaining.
        assertThatThrownBy(() -> jdbc.execute("DELETE FROM tenant"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void deleteIsNotGrantedByDefaultOnNewTables() throws Exception {
        // Proves the default-privilege change, not just the one-off tenant revoke:
        // a table created after V2_1 must not inherit DELETE.
        // Named rls_probe_* so Task 7's RlsCoverageTest, which is deny-by-default
        // over every table in the schema, skips it. Do not rename.
        //
        // Created via a direct connection as the container's owner role (the
        // same role migrations run as), not via the onboarding_app-bound
        // `jdbc` bean: onboarding_app has no CREATE privilege on schema public
        // (Postgres 15+ no longer grants that to PUBLIC by default), and if it
        // created its own table it would be the table's owner, who always has
        // DELETE regardless of any default-privilege revocation -- defeating
        // the point of this test.
        try (Connection owner = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             Statement stmt = owner.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS rls_probe_delete (id uuid PRIMARY KEY)");
            stmt.execute("GRANT SELECT, INSERT ON rls_probe_delete TO onboarding_app");
        }

        assertThatThrownBy(() -> jdbc.execute("DELETE FROM rls_probe_delete"))
                .hasStackTraceContaining("permission denied");
    }
}
