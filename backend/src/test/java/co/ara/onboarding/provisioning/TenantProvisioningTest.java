package co.ara.onboarding.provisioning;

import co.ara.onboarding.authz.RoleRepository;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantProvisioningTest extends PostgresTestBase {

    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roles;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void provisioningSeedsTwelveRolesAndAnInvitedAdministrator() {
        UUID tenantId = provisioning.provision(
                "newco", "New Co", "admin@newco.example", "New Co Admin");

        fixture.runAs(tenantId, () -> {
            assertThat(roles.findAll()).hasSize(12);
            var admin = users.findByTenantIdAndEmailIgnoreCase(tenantId, "admin@newco.example");
            assertThat(admin).isPresent();
            assertThat(admin.get().getStatus()).isEqualTo(UserStatus.INVITED);
        });
    }

    /**
     * Not in the plan. provision() binds a tenant to TenantContext mid-method
     * because seeding writes to RLS-protected tables, and TenantContext is a
     * ThreadLocal. Leaving it set would hand the next request served by this
     * thread a tenant it never asked for -- reads scoped to a stranger's data,
     * with no error anywhere. The pool makes that reuse routine, not rare.
     */
    @Test
    void provisioningDoesNotLeakTenantContextToTheCallingThread() {
        assertThat(TenantContext.getOrNull())
                .as("precondition: this test thread starts with no tenant bound")
                .isNull();

        provisioning.provision("leakcheck", "Leak Check",
                "admin@leakcheck.example", "Leak Check Admin");

        assertThat(TenantContext.getOrNull())
                .as("provision() must restore the caller's tenant context")
                .isNull();
    }

    /**
     * Not in the plan. The seeded roles are the whole point of provisioning, so
     * "twelve rows exist" is a weak assertion: it would hold for twelve roles with
     * no grants at all. This checks the Administrator template actually
     * round-tripped its grants and scopes into role_grant.
     */
    @Test
    void seededRolesCarryTheirTemplateGrants() {
        UUID tenantId = provisioning.provision(
                "grantcheck", "Grant Check", "admin@grantcheck.example", "Grant Check Admin");

        fixture.runAs(tenantId, () -> {
            var administrator = roles.findByTenantIdAndName(tenantId, "Administrator").orElseThrow();
            assertThat(administrator.isSystemTemplate()).isTrue();
            assertThat(administrator.getGrants())
                    .as("Administrator must be seeded with its full template grant set")
                    .hasSize(16);
        });
    }
}
