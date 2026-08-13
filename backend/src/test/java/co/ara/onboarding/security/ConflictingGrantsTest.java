package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 5 — conflicting grants resolve to the widest, deterministically.
 *
 * Run twice with the roles assigned in opposite order. There are no deny grants
 * (spec 6.5), so resolution can only widen and no precedence rule exists to get
 * wrong -- but an implementation that iterated roles and kept the last scope seen
 * would pass one ordering and fail the other.
 */
class ConflictingGrantsTest extends SecurityTestBase {

    @Test
    void allWinsOverAssignedWhicheverRoleIsAssignedFirst() throws Exception {
        assertResolvesToAll("conflict-a", true);
        assertResolvesToAll("conflict-b", false);
    }

    private void assertResolvesToAll(String slug, boolean allRoleFirst) throws Exception {
        UUID tenant = fixture.createTenant(slug);
        var user = new AtomicReference<AppUser>();

        fixture.runAs(tenant, () -> {
            AppUser viewer = fixture.createUserWithPassword(tenant, "conflict@example.com", "long-enough-password");
            user.set(viewer);

            // Owned by somebody else, so only an ALL grant can reach it.
            UUID other = fixture.createUser(tenant, "someone@example.com");
            fixture.createCustomer(tenant, "Everyones", other, null, null);

            UUID allRole = roles.createRole("Wide", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            UUID assignedRole = roles.createRole("Narrow", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));

            if (allRoleFirst) {
                roles.assignRole(viewer.getId(), allRole);
                roles.assignRole(viewer.getId(), assignedRole);
            } else {
                roles.assignRole(viewer.getId(), assignedRole);
                roles.assignRole(viewer.getId(), allRole);
            }
        });

        mvc.perform(as(get("/api/t/" + slug + "/customers"), user.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(1))
           .andExpect(jsonPath("$.content[0].displayName").value("Everyones"));
    }
}
