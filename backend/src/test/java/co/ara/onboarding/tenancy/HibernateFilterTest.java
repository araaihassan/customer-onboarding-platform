package co.ara.onboarding.tenancy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.identity.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HibernateFilterTest extends PostgresTestBase {

    @Autowired EntityManager entityManager;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void tenantFilterIsRegisteredNowThatAConcreteEntityExists() {
        var names = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class)
                .getDefinedFilterNames();

        assertThat(names)
            .as("the guard in TenantConnectionCustomizer must now pass, not silently skip")
            .contains("tenantFilter");
    }

    /**
     * Registration is not enforcement. A query returning only tenant A's rows
     * would look identical whether the restriction came from the Hibernate
     * filter or from RLS underneath, so that outcome alone cannot tell the two
     * apart -- see RlsIsolationTest for RLS proved through raw JDBC. To prove
     * the *filter* specifically, this captures the actual SQL text Hibernate
     * sends to the driver and asserts it carries a tenant_id predicate that
     * findAll() never asks for on its own. RLS is enforced by Postgres against
     * the query plan; it never rewrites the client-submitted SQL text, so a
     * tenant_id predicate appearing in the emitted SQL can only have been added
     * by the application-layer filter.
     */
    @Test
    void tenantFilterExcludesOtherTenantsRowsAtTheSqlLevel() {
        UUID tenantA = fixture.createTenant("filter-a");
        UUID tenantB = fixture.createTenant("filter-b");

        fixture.runAs(tenantA, () -> users.save(newUser(tenantA, "a-user@example.com")));
        fixture.runAs(tenantB, () -> users.save(newUser(tenantB, "b-user@example.com")));

        Logger sqlLogger = (Logger) LoggerFactory.getLogger("org.hibernate.SQL");
        Level previousLevel = sqlLogger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        sqlLogger.addAppender(appender);
        sqlLogger.setLevel(Level.DEBUG);

        try {
            fixture.runAs(tenantA, () -> {
                List<AppUser> visible = users.findAll();
                assertThat(visible)
                        .as("only tenant A's user should come back")
                        .extracting(AppUser::getEmail)
                        .containsExactly("a-user@example.com");
            });
        } finally {
            sqlLogger.setLevel(previousLevel);
            sqlLogger.detachAppender(appender);
        }

        String selectFromAppUser = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(sql -> sql.toLowerCase().contains("from app_user"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no SELECT against app_user was captured"));

        // tenant_id is also a plain SELECTed column (it's a mapped field), so a bare
        // "contains tenant_id" check would pass even with the filter disabled --
        // confirmed empirically by temporarily short-circuiting the guard in
        // TenantConnectionCustomizer.bind() to false, which still selects
        // "...au1_0.tenant_id..." in the column list. The only thing findAll() ever
        // adds on its own is column list + FROM; it never emits a WHERE clause for a
        // no-argument findAll(). So the presence of a WHERE clause AT ALL, containing
        // tenant_id, is what specifically proves the Hibernate filter added it.
        String lowerSql = selectFromAppUser.toLowerCase();
        int whereIndex = lowerSql.indexOf(" where ");
        assertThat(whereIndex)
                .as("findAll() has no predicate of its own; a WHERE clause appearing at all "
                        + "means something added one, and only the Hibernate filter could have -- "
                        + "RLS never rewrites client SQL text, see class javadoc")
                .isGreaterThan(-1);
        assertThat(lowerSql.substring(whereIndex))
                .as("the WHERE clause added to findAll() must be the tenant filter's predicate")
                .contains("tenant_id");
    }

    private AppUser newUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(Uuid7.generate());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setPasswordHash("x");
        u.setFullName("Test Person");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }
}
