package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;

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

    /**
     * The journey side of the same claim, through CaseDescriptor's assignedScope
     * (case_participant, not a bare ownerUserId column) -- a case reachable only
     * through TEAM, one only through ASSIGNED (CaseService.create makes the
     * customer's owner an OWNER participant), and one through neither.
     */
    @Test
    void caseScopesFromSeparateRolesUnion() throws Exception {
        UUID tenant = fixture.createTenant("case-multi-role");
        var user = new AtomicReference<AppUser>();
        var byTeam = new AtomicReference<UUID>();
        var byAssigned = new AtomicReference<UUID>();
        var unreachable = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            AppUser viewer = fixture.createUserWithPassword(tenant, "casemulti@example.com", "long-enough-password");
            user.set(viewer);

            UUID myTeam = fixture.createTeam(tenant, "Case Union Team");
            fixture.addToTeam(tenant, viewer.getId(), myTeam);
            UUID templateId = journey.publishedTemplate();

            UUID teamCustomer = fixture.createCustomer(tenant, "ByTeam", null, null, myTeam);
            byTeam.set(cases.create(new CreateCaseRequest(teamCustomer, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id());

            UUID assignedCustomer = fixture.createCustomer(tenant, "ByOwner", viewer.getId(), null, null);
            byAssigned.set(cases.create(new CreateCaseRequest(assignedCustomer, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id());

            UUID unreachableCustomer = fixture.createCustomer(tenant, "Unreachable", null, null, null);
            unreachable.set(cases.create(new CreateCaseRequest(unreachableCustomer, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id());

            UUID teamRole = roles.createRole("Case Team Grant", "", Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM));
            UUID assignedRole = roles.createRole("Case Assigned Grant", "",
                    Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
            UUID workflowViewRole = roles.createRole("Case Workflow View", "",
                    Map.of(PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(viewer.getId(), teamRole);
            roles.assignRole(viewer.getId(), assignedRole);
            roles.assignRole(viewer.getId(), workflowViewRole);
        });

        mvc.perform(as(get("/api/t/case-multi-role/cases/" + byTeam.get()), user.get()))
           .andExpect(status().isOk());
        mvc.perform(as(get("/api/t/case-multi-role/cases/" + byAssigned.get()), user.get()))
           .andExpect(status().isOk());
        mvc.perform(as(get("/api/t/case-multi-role/cases/" + unreachable.get()), user.get()))
           .andExpect(status().isNotFound());
    }
}
