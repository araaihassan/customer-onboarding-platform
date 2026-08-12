package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPersistenceTest extends PostgresTestBase {

    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void emailIsUniqueWithinTenantButNotAcrossTenants() {
        UUID tenantA = fixture.createTenant("alpha");
        UUID tenantB = fixture.createTenant("beta");

        fixture.runAs(tenantA, () -> users.save(newUser(tenantA, "shared@example.com")));
        fixture.runAs(tenantB, () -> users.save(newUser(tenantB, "shared@example.com")));

        // The exception must propagate OUT of runAs, not be caught inside it: once
        // saveAndFlush throws, Spring's transactional advice on the repository call
        // has already marked the enclosing TransactionTemplate transaction
        // rollback-only. Catching the exception inside the runnable lets that
        // runnable return normally, so runAs's TransactionTemplate then attempts to
        // COMMIT a rollback-only transaction and throws UnexpectedRollbackException
        // instead -- masking the DataIntegrityViolationException this test means to
        // assert. Asserting on the runAs call itself avoids that: the exception
        // propagates before any commit is attempted.
        assertThatThrownBy(() ->
            fixture.runAs(tenantA, () -> users.saveAndFlush(newUser(tenantA, "shared@example.com"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsUserByEmailWithinTenant() {
        UUID tenant = fixture.createTenant("gamma");
        fixture.runAs(tenant, () -> {
            users.save(newUser(tenant, "person@example.com"));
            assertThat(users.findByTenantIdAndEmailIgnoreCase(tenant, "PERSON@example.com"))
                    .isPresent();
        });
    }

    private AppUser newUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(UUID.randomUUID());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setPasswordHash("x");
        u.setFullName("Test Person");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }
}
