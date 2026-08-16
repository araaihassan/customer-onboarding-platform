package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 8 — direct API access, bypassing whatever the UI chose to render.
 *
 * The permission-aware UI is convenience, never security (spec 10.3). Everything
 * here is a request the interface would never have offered.
 */
class DirectApiAccessTest extends SecurityTestBase {

    @Test
    void portalUserIsForbiddenFromInternalSurfaces() throws Exception {
        UUID tenant = fixture.createTenant("portal-direct");
        AppUser portal = fixture.createPortalUser(tenant, "contact@example.com");

        mvc.perform(as(get("/api/t/portal-direct/admin/users"), portal))
           .andExpect(status().isForbidden());

        mvc.perform(as(post("/api/t/portal-direct/customers"), portal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
           .andExpect(status().isForbidden());

        mvc.perform(as(post("/api/t/portal-direct/admin/roles"), portal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"description\":\"\",\"grants\":{}}"))
           .andExpect(status().isForbidden());
    }

    /**
     * The userType boundary. A portal user holding an internal role would see staff
     * surfaces, so assignment must refuse regardless of who is asking — this request
     * comes from a full administrator, and is still rejected.
     */
    @Test
    void portalUserCannotBeAssignedAnInternalRole() throws Exception {
        UUID tenant = fixture.createTenant("portal-role");
        var admin = new AtomicReference<AppUser>();
        var roleId = new AtomicReference<UUID>();
        AppUser portal = fixture.createPortalUser(tenant, "portalrole@example.com");

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "assigner@example.com"));
            roleId.set(roles.createRole("Internal", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
        });

        mvc.perform(as(post("/api/t/portal-role/admin/users/" + portal.getId() + "/roles"), admin.get())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"" + roleId.get() + "\"}"))
           .andExpect(status().isBadRequest());
    }

    @Test
    void platformEndpointRequiresPlatformAdminCredentials() throws Exception {
        String body = "{\"slug\":\"sneaky\",\"name\":\"Sneaky\","
                + "\"adminEmail\":\"a@b.example\",\"adminFullName\":\"A B\"}";

        mvc.perform(post("/api/platform/tenants")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isUnauthorized());
    }

    /**
     * A tenant user's access token is not platform-admin authority. Without this,
     * "requires credentials" could be satisfied by any authenticated caller.
     */
    @Test
    void aTenantAccessTokenDoesNotUnlockThePlatformEndpoint() throws Exception {
        UUID tenant = fixture.createTenant("platform-token");
        AppUser admin = fixture.createAdminUser(tenant, "tenantadmin@example.com");

        String body = "{\"slug\":\"sneaky2\",\"name\":\"Sneaky\","
                + "\"adminEmail\":\"a@b.example\",\"adminFullName\":\"A B\"}";

        mvc.perform(as(post("/api/platform/tenants"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isUnauthorized());
    }

    @Test
    void platformAdminCredentialsAreAccepted() throws Exception {
        fixture.createPlatformAdmin("ops@vendor.example", "platform-admin-password");

        String body = "{\"slug\":\"provisioned-ok\",\"name\":\"Provisioned\","
                + "\"adminEmail\":\"admin@provisioned.example\",\"adminFullName\":\"Admin\"}";

        mvc.perform(post("/api/platform/tenants")
                        .with(httpBasic("ops@vendor.example", "platform-admin-password"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
           .andExpect(status().isOk());
    }

    /**
     * Iterated explicitly rather than sampled, so adding an endpoint without adding
     * it here is a visible gap in the list rather than an invisible one.
     *
     * BOTH directions are asserted, and the second is what makes the first mean
     * anything. With .anyRequest().authenticated(), a request to a path that does not
     * exist also answers 401 — so a typo in this list would pass silently and prove
     * nothing. Repeating the sweep with an administrator's token and asserting the
     * response is NOT 401 is what confirms every path actually resolves to a handler.
     */
    @Test
    void everyEndpointRefusesAnonymousAndResolvesForAnAdministrator() throws Exception {
        UUID tenant = fixture.createTenant("anon-sweep");
        var admin = new AtomicReference<AppUser>();
        var customerId = new AtomicReference<UUID>();
        var userId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "sweep@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Anon", null, null, null));
            userId.set(fixture.createUser(tenant, "anon@example.com"));
        });

        String base = "/api/t/anon-sweep";
        var paths = List.<Supplier<MockHttpServletRequestBuilder>>of(
                () -> post(base + "/customers"),
                () -> MockMvcRequestBuilders.put(base + "/customers/" + customerId.get()),
                () -> post(base + "/customers/" + customerId.get() + "/deactivate"),
                () -> post(base + "/customers/" + customerId.get() + "/contacts"),
                () -> get(base + "/customers"),
                () -> get(base + "/customers/" + customerId.get()),
                () -> get(base + "/me"),
                () -> get(base + "/admin/users"),
                () -> post(base + "/admin/users"),
                () -> post(base + "/admin/users/" + userId.get() + "/roles"),
                () -> post(base + "/admin/users/" + userId.get() + "/deactivate"),
                () -> post(base + "/admin/roles"),
                () -> get(base + "/admin/departments"),
                () -> post(base + "/admin/departments"),
                () -> get(base + "/admin/teams"),
                () -> post(base + "/admin/teams"),
                () -> get(base + "/admin/permissions"));

        for (var path : paths) {
            mvc.perform(path.get().contentType(MediaType.APPLICATION_JSON).content("{}"))
               .andExpect(status().isUnauthorized());
        }

        for (var path : paths) {
            // 404 is the signal, not 401. An AUTHENTICATED request to a path that does
            // not exist answers 404 — it is only the anonymous case that answers 401 for
            // everything alike. Asserting "not 401" here therefore proved nothing, which
            // a deliberately misspelled path confirmed by passing.
            //
            // The empty bodies mean a handler that does run may reject them with 400, or
            // throw and have MockMvc rethrow rather than convert. Both mean the request
            // reached a controller, which is all this loop needs to establish.
            int status;
            try {
                status = mvc.perform(as(path.get(), admin.get())
                                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andReturn().getResponse().getStatus();
            } catch (Exception handlerThrew) {
                continue;   // reached a handler; that is what we are proving
            }
            assertThat(status)
                    .as("this path must resolve to a real handler, or its 401 above proves nothing")
                    .isNotEqualTo(404);
        }
    }
}
