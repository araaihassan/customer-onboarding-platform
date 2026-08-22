package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 3 — correct permission, insufficient scope.
 *
 * Both halves are asserted. Without the 200, this test would pass against a system
 * that simply denies everything, which is the failure mode a scope test is most
 * likely to hide.
 */
class InsufficientScopeTest extends SecurityTestBase {

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;

    @Test
    void caseViewAtTeamCannotReadAnotherTeamsCase() throws Exception {
        UUID tenant = fixture.createTenant("case-scope-team");
        var viewer = new AtomicReference<AppUser>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            AppUser v = fixture.createUserWithPassword(tenant, "caseteam@example.com", "long-enough-password");
            viewer.set(v);

            UUID myTeam = fixture.createTeam(tenant, "My Case Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other Case Team");
            fixture.addToTeam(tenant, v.getId(), myTeam);

            UUID templateId = journey.publishedTemplate();
            UUID myCustomer = fixture.createCustomer(tenant, "Mine", null, null, myTeam);
            UUID theirCustomer = fixture.createCustomer(tenant, "Theirs", null, null, otherTeam);
            mine.set(cases.create(new CreateCaseRequest(myCustomer, templateId, Map.of())).id());
            theirs.set(cases.create(new CreateCaseRequest(theirCustomer, templateId, Map.of())).id());

            UUID role = roles.createRole("Case Team Viewer", "",
                    Map.of(PermissionKeys.CASE_VIEW, Scope.TEAM, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(v.getId(), role);
        });

        // 404 rather than 403: out of scope must be indistinguishable from absent.
        mvc.perform(as(get("/api/t/case-scope-team/cases/" + theirs.get()), viewer.get()))
           .andExpect(status().isNotFound());

        mvc.perform(as(get("/api/t/case-scope-team/cases/" + mine.get()), viewer.get()))
           .andExpect(status().isOk());
    }

    /** milestone.complete at TEAM cannot complete inside a case belonging to a different team. */
    @Test
    void milestoneCompleteAtTeamCannotSatisfyInAnotherTeamsCase() throws Exception {
        UUID tenant = fixture.createTenant("milestone-scope-team");
        var actor = new AtomicReference<AppUser>();
        var caseId = new AtomicReference<UUID>();
        var requirementId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            AppUser a = fixture.createUserWithPassword(tenant, "milestoneteam@example.com", "long-enough-password");
            actor.set(a);

            UUID myTeam = fixture.createTeam(tenant, "My Milestone Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other Milestone Team");
            fixture.addToTeam(tenant, a.getId(), myTeam);

            UUID templateId = journey.publishedTemplate();
            UUID theirCustomer = fixture.createCustomer(tenant, "Theirs", null, null, otherTeam);
            UUID id = cases.create(new CreateCaseRequest(theirCustomer, templateId, Map.of())).id();
            caseId.set(id);
            requirementId.set(cases.roadmap(id).stages().get(0).milestones().get(0).requirements().get(0).id());

            UUID role = roles.createRole("Milestone Team Completer", "",
                    Map.of(PermissionKeys.MILESTONE_COMPLETE, Scope.TEAM));
            roles.assignRole(a.getId(), role);
        });

        mvc.perform(as(post("/api/t/milestone-scope-team/cases/" + caseId.get()
                        + "/requirements/" + requirementId.get() + "/satisfy"), actor.get())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isNotFound());
    }

    @Test
    void outOfScopeRecordIs404AndInScopeRecordIs200() throws Exception {
        UUID tenant = fixture.createTenant("sec-scope-404");
        var user = new AtomicReference<AppUser>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            AppUser viewer = fixture.createUserWithPassword(tenant, "team@example.com", "long-enough-password");
            user.set(viewer);

            UUID myTeam = fixture.createTeam(tenant, "My Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other Team");
            fixture.addToTeam(tenant, viewer.getId(), myTeam);

            mine.set(fixture.createCustomer(tenant, "Mine", null, null, myTeam));
            theirs.set(fixture.createCustomer(tenant, "Theirs", null, null, otherTeam));

            UUID role = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(viewer.getId(), role);
        });

        // 404 rather than 403: out of scope must be indistinguishable from absent.
        mvc.perform(as(get("/api/t/sec-scope-404/customers/" + theirs.get()), user.get()))
           .andExpect(status().isNotFound());

        mvc.perform(as(get("/api/t/sec-scope-404/customers/" + mine.get()), user.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.displayName").value("Mine"));
    }

    @Test
    void listingShowsOnlyTheInScopeRecord() throws Exception {
        UUID tenant = fixture.createTenant("scope-list");
        var user = new AtomicReference<AppUser>();

        fixture.runAs(tenant, () -> {
            AppUser viewer = fixture.createUserWithPassword(tenant, "listteam@example.com", "long-enough-password");
            user.set(viewer);

            UUID myTeam = fixture.createTeam(tenant, "List Team");
            UUID otherTeam = fixture.createTeam(tenant, "Other List Team");
            fixture.addToTeam(tenant, viewer.getId(), myTeam);

            fixture.createCustomer(tenant, "In Team", null, null, myTeam);
            fixture.createCustomer(tenant, "Out Of Team", null, null, otherTeam);

            UUID role = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(viewer.getId(), role);
        });

        mvc.perform(as(get("/api/t/scope-list/customers"), user.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(1))
           .andExpect(jsonPath("$.content[0].displayName").value("In Team"));
    }

    @Test
    void teamScopeResolvesAfterAddingMembershipViaApi() throws Exception {
        UUID tenant = fixture.createTenant("scope-api-team");
        var viewer = new AtomicReference<AppUser>();
        var teamId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID myTeam = fixture.createTeam(tenant, "API Team");
            teamId.set(myTeam);
            customerId.set(fixture.createCustomer(tenant, "Scoped Customer", null, null, myTeam));

            AppUser viewerUser = fixture.createUserWithPassword(tenant, "apiteam@example.com", "long-enough-password");
            viewer.set(viewerUser);

            UUID teamManageRole = roles.createRole("Team Manager", "",
                    Map.of(PermissionKeys.TEAM_MANAGE, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
            UUID customerViewRole = roles.createRole("Customer Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(viewerUser.getId(), teamManageRole);
            roles.assignRole(viewerUser.getId(), customerViewRole);
        });

        // Before membership added via API: out of scope, so 404.
        mvc.perform(as(get("/api/t/scope-api-team/customers/" + customerId.get()), viewer.get()))
           .andExpect(status().isNotFound());

        // Add membership through the API endpoint.
        mvc.perform(as(post("/api/t/scope-api-team/admin/teams/" + teamId.get() + "/members")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + viewer.get().getId() + "\"}"),
                viewer.get()))
           .andExpect(status().isNoContent());

        // After membership added: in scope.
        mvc.perform(as(get("/api/t/scope-api-team/customers/" + customerId.get()), viewer.get()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.displayName").value("Scoped Customer"));
    }
}
