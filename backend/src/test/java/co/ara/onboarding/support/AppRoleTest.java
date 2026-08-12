package co.ara.onboarding.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

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
}
