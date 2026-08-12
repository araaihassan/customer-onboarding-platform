package co.ara.onboarding.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Consumer;

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

    /**
     * JdbcTemplate bound to the container's owner role, for DDL and GRANT only.
     * Never assert privilege or RLS behaviour through this — the owner bypasses
     * both, so an assertion made here proves nothing.
     */
    protected static JdbcTemplate ownerJdbc() {
        var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        return new JdbcTemplate(ds);
    }

    /**
     * Runs a whole SET -&gt; act -&gt; assert sequence against ONE explicitly held
     * JDBC connection, bound to the given role.
     *
     * Session-scoped state -- SET, RESET, and anything an RLS policy reads via
     * current_setting -- only survives across statements issued on the same
     * physical connection. Neither the autowired {@code jdbc} bean
     * (HikariCP-pooled) nor {@link #ownerJdbc()} (a fresh
     * DriverManagerDataSource connection on every call) makes that guarantee:
     * HikariCP's thread-local LIFO reuse currently happens to hand a
     * single-threaded test back the same connection, but that is an
     * implementation detail, not a public contract, and ownerJdbc() opens a
     * brand new connection every single call by construction, so a SET
     * through it is never visible to a later SELECT through it. Any test
     * whose correctness depends on a SET made earlier in the same test must
     * go through this helper instead of jdbc or ownerJdbc().
     */
    protected static void withHeldConnection(String username, String password,
                                              Consumer<JdbcTemplate> sequence) {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), username, password)) {
            sequence.accept(new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    /** {@link #withHeldConnection} bound to onboarding_app. */
    protected static void withAppConnection(Consumer<JdbcTemplate> sequence) {
        withHeldConnection("onboarding_app", "onboarding_app", sequence);
    }

    /**
     * {@link #withHeldConnection} bound to the container's owner role.
     * As with ownerJdbc(), never assert RLS or privilege behaviour through
     * this -- the owner role is the container's superuser (see
     * flywayProperties() above), and superusers always bypass row security
     * regardless of FORCE, so an assertion here would prove nothing. The one
     * deliberate exception is documented at its use site in RlsIsolationTest.
     */
    protected static void withOwnerConnection(Consumer<JdbcTemplate> sequence) {
        withHeldConnection(POSTGRES.getUsername(), POSTGRES.getPassword(), sequence);
    }
}
