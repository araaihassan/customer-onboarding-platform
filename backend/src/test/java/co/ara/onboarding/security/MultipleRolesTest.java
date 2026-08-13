package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 4 — multiple roles. The test that proves scopes union rather than nest.
 *
 * One record is reachable ONLY through the team grant, one ONLY through the
 * personal-owner grant, and one through neither. An implementation that kept a
 * single "widest" scope, or that treated TEAM as subsuming ASSIGNED, fails on the
 * record it does not cover.
 */
class MultipleRolesTest extends SecurityTestBase {

    @Test
    void scopesFromSeparateRolesUnion() throws Exception {
        UUID tenant = fixture.createTenant("multi-role");
        var user = new AtomicReference<AppUser>();

        fixture.runAs(tenant, () -> {
            AppUser viewer = fixture.createUserWithPassword(tenant, "multi@example.com", "long-enough-password");
            user.set(viewer);

            UUID myTeam = fixture.createTeam(tenant, "Union Team");
            fixture.addToTeam(tenant, viewer.getId(), myTeam);

            // Team-only: owned by nobody, but belonging to the user's team.
            fixture.createCustomer(tenant, "ByTeam", null, null, myTeam);
            // Assigned-only: owned by the user, in no team at all.
            fixture.createCustomer(tenant, "ByOwner", viewer.getId(), null, null);
            // Neither.
            fixture.createCustomer(tenant, "Unreachable", null, null, null);

            UUID teamRole = roles.createRole("Team Grant", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            UUID assignedRole = roles.createRole("Assigned Grant", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(viewer.getId(), teamRole);
            roles.assignRole(viewer.getId(), assignedRole);
        });

        mvc.perform(as(get("/api/t/multi-role/customers"), user.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(2))
           .andExpect(jsonPath("$.content[*].displayName",
                   containsInAnyOrder("ByTeam", "ByOwner")));
    }
}
