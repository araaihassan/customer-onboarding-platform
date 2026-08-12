package co.ara.onboarding.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
public abstract class PostgresTestBase {

    // Deliberately NOT @ServiceConnection: that annotation registers a
    // JdbcConnectionDetails bean that Spring Boot's DataSourceAutoConfiguration
    // prefers unconditionally over spring.datasource.* properties, which would
    // silently defeat appDatasourceProperties() below and leave the app
    // connected as the container's superuser. Both datasources are wired
    // explicitly instead: Flyway via spring.flyway.* (which FlywayAutoConfiguration
    // always builds directly from properties once spring.flyway.url is set,
    // bypassing connection-details beans) and the app datasource via
    // spring.datasource.* (which works here only because no competing
    // JdbcConnectionDetails bean exists).
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    static { POSTGRES.start(); }

    /**
     * Flyway runs as the container's superuser (the schema owner).
     * The application datasource is repointed to onboarding_app in Task 2,
     * once the migration that creates that role exists.
     */
    @DynamicPropertySource
    static void flywayProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    /**
     * The main application datasource connects as onboarding_app (created by
     * V2__app_role_and_tenant.sql), never as the container superuser. RLS does
     * not constrain superusers or table owners, so this is what makes every
     * tenant-isolation test in this project meaningful. See AppRoleTest.
     */
    @DynamicPropertySource
    static void appDatasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "onboarding_app");
        registry.add("spring.datasource.password", () -> "onboarding_app");
    }
}
