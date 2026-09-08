package co.ara.onboarding.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Consumer;

/**
 * ActiveProfiles("test") selects LoggingEmailSender. Without it no profile is
 * active, SmtpEmailSender's "!dev & !test" matches, and every test that sends mail
 * tries to open an SMTP connection.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresTestBase.MutableClockConfig.class)
public abstract class PostgresTestBase {

    /**
     * Overrides PlatformBeansConfig.clock() (Clock.systemUTC(), unqualified) with
     * a @Primary, advanceable one, so HoldTest-style tests can assert on elapsed
     * business days without sleeping for real. The bean name is deliberately NOT
     * "clock" -- two @Bean methods named clock() in different @Configuration
     * classes would collide, since Spring Boot disables bean-definition
     * overriding by default.
     */
    @TestConfiguration
    static class MutableClockConfig {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MutableClock clock;

    /**
     * Every test starts with a clean clock. This bean is a Spring singleton,
     * shared and cached across the entire suite (@SpringBootTest's context
     * caching), so an advance() left over from one test would otherwise leak
     * into the next one to run.
     */
    @BeforeEach
    void resetMutableClock() {
        clock.reset();
    }

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
     * The suite's onboarding_app password, generated per JVM run rather than
     * written down -- the same reasoning jwtSecret() below already applies to
     * app.jwt.secret. V2__app_role_and_tenant.sql creates the role with the
     * committed literal password 'onboarding_app', but DatabaseCredentialsGuard
     * now denylists that literal, so the suite cannot run on it: this is what
     * AppRolePasswordReconciler's AFTER_MIGRATE callback reconciles the role's
     * real password to instead, before appDatasourceProperties() below is ever
     * used to open a connection. A single static field, generated once, is
     * shared by every place that used to hardcode the literal --
     * appDatasourceProperties() and withAppConnection() both reference it, so
     * they cannot drift from each other the way two independent literals could.
     */
    private static final String APP_PASSWORD = generateAppPassword();

    private static String generateAppPassword() {
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

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
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // DatabaseCredentialsGuard and AppRolePasswordReconciler both bind
        // DB_APP_PASSWORD directly (not spring.datasource.password), so the
        // suite has to supply it under this key too.
        registry.add("DB_APP_PASSWORD", () -> APP_PASSWORD);
    }

    /**
     * The suite's JWT signing secret, generated per JVM run rather than written
     * down. application.yml ships no fallback and JwtProperties refuses to start
     * without a usable secret, so the suite must supply one — but a literal in
     * src/test/resources is a published secret like any other, and this project
     * denylists those. Generating it removes the value from the repository instead
     * of adding it to the denylist, which is the only version of that fix that
     * does not denylist the very value the suite runs on.
     *
     * The guard still runs in full against this: it is well over the 32-byte
     * minimum and is not a denylisted placeholder, so nothing here is an exemption.
     * Nothing in the suite depends on the secret being stable across runs — tokens
     * are minted and verified inside a single run.
     */
    @DynamicPropertySource
    static void jwtSecret(DynamicPropertyRegistry registry) {
        byte[] secret = new byte[48];
        new java.security.SecureRandom().nextBytes(secret);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        registry.add("app.jwt.secret", () -> encoded);
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
        withHeldConnection("onboarding_app", APP_PASSWORD, sequence);
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
