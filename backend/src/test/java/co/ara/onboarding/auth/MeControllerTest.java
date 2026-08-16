package co.ara.onboarding.auth;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class MeControllerTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired RoleService roles;
    @Autowired TokenService tokens;
    @Autowired TenantFixture fixture;

    @Test
    void meReturnsEffectivePermissionsWithScopes() throws Exception {
        UUID tenant = fixture.createTenant("me-co");
        var user = fixture.createUserWithPassword(tenant, "me@example.com", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID role = roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(user.getId(), role);
        });

        mvc.perform(get("/api/t/me-co/me")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(user)))
           .andExpect(status().isOk())
           // An array, not a bare value -- a permission can be held at several scopes.
           .andExpect(jsonPath("$.permissions['customer.view']", hasItem("TEAM")))
           .andExpect(jsonPath("$.userType").value("INTERNAL"))
           .andExpect(jsonPath("$.email").value("me@example.com"));
    }

    @Test
    void meRequiresAuthentication() throws Exception {
        fixture.createTenant("me-anon");
        mvc.perform(get("/api/t/me-anon/me")).andExpect(status().isUnauthorized());
    }

    /**
     * Not in the plan, and the case the UI actually depends on. Scopes union across
     * roles, so the response must carry every scope a permission is held at — a
     * response keeping only one would make useHasPermission wrong in exactly the
     * situation the scope model exists for.
     */
    @Test
    void permissionsUnionScopesAcrossRoles() throws Exception {
        UUID tenant = fixture.createTenant("me-union");
        var user = fixture.createUserWithPassword(tenant, "union@example.com", "long-enough-password");

        fixture.runAs(tenant, () -> {
            UUID teamRole = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            UUID assignedRole = roles.createRole("Own Records", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.getId(), teamRole);
            roles.assignRole(user.getId(), assignedRole);
        });

        mvc.perform(get("/api/t/me-union/me")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(user)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions['customer.view']",
                   containsInAnyOrder("ASSIGNED", "TEAM")));
    }

    /**
     * Not in the plan. A user holding nothing must get an empty permission map rather
     * than an error or an absent field — the frontend reads it unconditionally, and
     * "no permissions" is a normal state for a freshly invited user.
     */
    @Test
    void userWithNoRolesGetsAnEmptyPermissionMap() throws Exception {
        UUID tenant = fixture.createTenant("me-empty");
        var user = fixture.createUserWithPassword(tenant, "empty@example.com", "long-enough-password");

        mvc.perform(get("/api/t/me-empty/me")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(user)))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions").isMap())
           .andExpect(jsonPath("$.permissions").isEmpty());
    }

    /**
     * Not in the plan. /me reads the caller's own record, and the token's tenant is
     * checked by JwtAuthenticationFilter — so presenting a valid token against
     * another tenant's path must not return that user's profile.
     */
    @Test
    void tokenFromAnotherTenantCannotReadMe() throws Exception {
        UUID tenantA = fixture.createTenant("me-tenant-a");
        fixture.createTenant("me-tenant-b");
        var userInA = fixture.createUserWithPassword(tenantA, "a@example.com", "long-enough-password");

        mvc.perform(get("/api/t/me-tenant-b/me")
                    .header("Authorization", "Bearer " + tokens.issueAccessToken(userInA)))
           .andExpect(status().isUnauthorized());
    }
}
