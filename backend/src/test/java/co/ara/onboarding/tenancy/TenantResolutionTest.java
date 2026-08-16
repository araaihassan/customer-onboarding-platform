package co.ara.onboarding.tenancy;

import co.ara.onboarding.auth.TokenService;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts against real endpoints. These tests used TenantDebugController, which
 * Task 20 deleted along with its two standing exemptions — the ArchUnit
 * repository clause and the SecurityConfig permitAll.
 */
@AutoConfigureMockMvc(addFilters = true)
class TenantResolutionTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;
    @Autowired TokenService tokens;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @BeforeEach
    void seedTenant() {
        if (tenants.findBySlug("acme").isEmpty()) {
            Tenant t = new Tenant();
            t.setId(Uuid7.generate());
            t.setSlug("acme");
            t.setName("Acme Corp");
            t.setStatus(TenantStatus.ACTIVE);
            tenants.save(t);
        }
    }

    /**
     * 401 rather than 404 is the assertion: the tenant resolved, and only then did
     * authentication reject the request. TenantContextFilter runs at order -110, ahead
     * of Spring Security's chain at -100, and this is what proves that ordering.
     */
    @Test
    void bindsTenantFromPathPrefix() throws Exception {
        mvc.perform(get("/api/t/acme/customers"))
           .andExpect(status().isUnauthorized());
    }

    /**
     * 404, not 401. An unknown tenant is rejected before authentication is even
     * considered, so the endpoint cannot be used to probe which tenants exist.
     */
    @Test
    void unknownTenantSlugReturns404() throws Exception {
        mvc.perform(get("/api/t/does-not-exist/customers"))
           .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantReturns404() throws Exception {
        Tenant t = new Tenant();
        t.setId(Uuid7.generate());
        t.setSlug("suspended-co");
        t.setName("Suspended Co");
        t.setStatus(TenantStatus.SUSPENDED);
        tenants.save(t);

        mvc.perform(get("/api/t/suspended-co/customers"))
           .andExpect(status().isNotFound());
    }

    /**
     * Replaces the old assertion that read app.tenant_id back through a debug
     * endpoint, and proves more than it did. Over a real HTTP request this response
     * requires the whole chain: the filter resolves the tenant, the transaction
     * binder sets app.tenant_id on the connection, permission resolution reads three
     * RLS-protected tables through it, the gate passes, and the scope predicate
     * matches. If the GUC were not bound, RLS would return no roles, the gate would
     * deny, and this would be 403 rather than 200.
     */
    @Test
    void aRealRequestBindsTheTenantAllTheWayToPostgres() throws Exception {
        UUID tenantId = tenants.findBySlug("acme").orElseThrow().getId();
        var token = new AtomicReference<String>();

        fixture.runAs(tenantId, () -> {
            var user = fixture.createUserWithPassword(tenantId, "resolve@acme.example", "long-enough-password");
            fixture.createCustomer(tenantId, "Acme Customer", null, null, null);
            UUID role = roles.createRole("Resolver", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user.getId(), role);
            token.set(tokens.issueAccessToken(user));
        });

        mvc.perform(get("/api/t/acme/customers").header("Authorization", "Bearer " + token.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content[?(@.displayName == 'Acme Customer')]").exists());
    }
}
