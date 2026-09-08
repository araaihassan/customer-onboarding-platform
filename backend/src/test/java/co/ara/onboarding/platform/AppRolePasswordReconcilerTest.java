package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves AppRolePasswordReconciler's ordering claim empirically, against a
 * database migrated from scratch -- this is the test that would have caught
 * the ordering risk if it had existed from the start.
 *
 * V2__app_role_and_tenant.sql always creates onboarding_app with the committed
 * literal password 'onboarding_app'. DatabaseCredentialsGuard now refuses to
 * start on that literal, so DB_APP_PASSWORD here is a real, freshly generated,
 * non-default value -- one nothing in this database has ever been set to
 * before. The app datasource successfully connecting with it is only possible
 * if AppRolePasswordReconciler's AFTER_MIGRATE callback ran and reconciled the
 * role's actual password before spring.datasource.* opened its first real
 * connection.
 *
 * Deliberately does NOT extend PostgresTestBase: that base class's container is
 * shared across the whole suite (a static field on the class that declares it,
 * not duplicated per subclass) and is reconciled to whatever password other
 * tests' contexts happen to supply. Proving this claim needs a container
 * migrated exactly once, under this test's own control, isolated from every
 * other test's reconciliation of the same shared role.
 */
@SpringBootTest
@ActiveProfiles("test")
class AppRolePasswordReconcilerTest {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /**
     * Deliberately not the literal V2 creates the role with, and not blank --
     * exactly the two values DatabaseCredentialsGuard refuses. A fresh random
     * value each run, so this proof can never be accused of coincidentally
     * matching whatever the role already happened to be set to.
     */
    private static final String ROTATED_PASSWORD = generateRotatedPassword();

    private static String generateRotatedPassword() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "onboarding_app");
        registry.add("spring.datasource.password", () -> ROTATED_PASSWORD);
        registry.add("DB_APP_PASSWORD", () -> ROTATED_PASSWORD);

        byte[] jwt = new byte[48];
        new SecureRandom().nextBytes(jwt);
        registry.add("app.jwt.secret",
                () -> Base64.getUrlEncoder().withoutPadding().encodeToString(jwt));
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void appDatasourceConnectsWithARotatedNonDefaultPasswordOnAFreshDatabase() {
        assertThat(ROTATED_PASSWORD)
                .as("the proof is worthless if this happens to equal the migration's own literal")
                .isNotEqualTo("onboarding_app");

        String currentUser = jdbc.queryForObject("SELECT current_user", String.class);

        assertThat(currentUser)
                .as("the autowired JdbcTemplate is bound to spring.datasource.*; it only connects "
                        + "at all if AppRolePasswordReconciler's AFTER_MIGRATE callback already "
                        + "reconciled onboarding_app's real password to DB_APP_PASSWORD before this "
                        + "connection was opened")
                .isEqualTo("onboarding_app");
    }

    /**
     * Same claim, proven a second, independent way: a raw JDBC connection opened
     * by hand with the rotated password, bypassing the autowired bean (and any
     * connection the pool may have opened and cached earlier for an unrelated
     * reason) entirely.
     */
    @Test
    void theRolesRealPasswordIsTheRotatedValueNotTheMigrationLiteral() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), "onboarding_app", ROTATED_PASSWORD)) {
            assertThat(connection.isValid(5)).isTrue();
        }
    }
}
