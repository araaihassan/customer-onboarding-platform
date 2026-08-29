package co.ara.onboarding.provisioning;

import co.ara.onboarding.authz.RoleRepository;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.tenancy.TenantContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc-based, not service-based, for the two tests below: bean validation
 * (@NotBlank, @Pattern) only fires through @Valid at the web layer, so it cannot
 * be exercised by calling TenantProvisioningService directly with positional
 * strings. Modelled on security.DirectApiAccessTest's existing calls against
 * this same endpoint.
 */
@AutoConfigureMockMvc
class TenantProvisioningTest extends PostgresTestBase {

    @Autowired TenantProvisioningService provisioning;
    @Autowired RoleRepository roles;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;
    @Autowired MockMvc mvc;

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
                    // 16 through sub-project 1; sub-project 2 Task 6 added
                    // workflow.view and workflow.manage (18), and Task 11 added the
                    // thirteen journey keys (31) -- to both the catalog and this
                    // template (RoleTemplateValidityTest.administratorGrantsEveryPermissionInTheCatalog
                    // is the guard that keeps this number honest).
                    .as("Administrator must be seeded with its full template grant set")
                    .hasSize(31);
        });
    }

    /**
     * A slug the resolver cannot parse (PathPrefixTenantResolver.SLUG_PATTERN,
     * ^[a-z0-9][a-z0-9-]{0,62}$) previously created a tenant that was permanently
     * unreachable: every request resolves no slug and answers 401, with no error
     * at creation time. Rejecting it here at creation is the fix.
     */
    @Test
    void aSlugTheResolverCannotParseIsRejectedAtCreation() throws Exception {
        fixture.createPlatformAdmin("ops@slugtest.example", "platform-admin-password");

        mvc.perform(post("/api/platform/tenants")
                        .with(httpBasic("ops@slugtest.example", "platform-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"Acme","name":"Acme Corp","adminEmail":"a@acme.test","adminFullName":"Admin"}"""))
                .andExpect(status().isBadRequest());
    }

    /**
     * tenant.slug is a plain UNIQUE constraint (V2), case-sensitive. Before this
     * fix the second call's DataIntegrityViolationException escaped as a raw 500;
     * it must now be translated to 409, the conflict the caller can act on.
     */
    @Test
    void aDuplicateSlugIsAConflictNotAServerError() throws Exception {
        fixture.createPlatformAdmin("ops@duptest.example", "platform-admin-password");

        String body = """
                {"slug":"dup","name":"First","adminEmail":"a@x.test","adminFullName":"A"}""";
        mvc.perform(post("/api/platform/tenants")
                        .with(httpBasic("ops@duptest.example", "platform-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mvc.perform(post("/api/platform/tenants")
                        .with(httpBasic("ops@duptest.example", "platform-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"slug":"dup","name":"Second","adminEmail":"b@x.test","adminFullName":"B"}"""))
                .andExpect(status().isConflict());
    }
}
