package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 7 — changed role permissions take effect against an existing session.
 *
 * The same unexpired access token is reused across the revocation. This is what
 * proves permissions are neither embedded in the token nor cached across requests
 * (spec 6.7, 7.2): a token carrying claims, or a singleton-scoped resolution, would
 * keep working here.
 */
class ChangedPermissionsTest extends SecurityTestBase {

    @Test
    void revokingAGrantAppliesToAnAlreadyIssuedToken() throws Exception {
        UUID tenant = fixture.createTenant("perm-change");
        var admin = new AtomicReference<AppUser>();
        var viewer = new AtomicReference<AppUser>();
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "changeadmin@example.com"));
            AppUser v = fixture.createUserWithPassword(tenant, "changing@example.com", "long-enough-password");
            viewer.set(v);
            roleId.set(roles.createRole("Temporary", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            roles.assignRole(v.getId(), roleId.get());
        });

        // One token, issued once, used on both sides of the revocation.
        String token = tokens.issueAccessToken(viewer.get());

        mvc.perform(get("/api/t/perm-change/customers").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk());

        mvc.perform(as(put("/api/t/perm-change/admin/roles/" + roleId.get() + "/grants"), admin.get())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isNoContent());

        mvc.perform(get("/api/t/perm-change/customers").header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }
}
