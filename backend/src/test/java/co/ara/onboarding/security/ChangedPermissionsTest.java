package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;

    @Test
    void revokingCaseAdvanceAppliesToAnAlreadyIssuedToken() throws Exception {
        UUID tenant = fixture.createTenant("case-perm-change");
        var admin = new AtomicReference<AppUser>();
        var actor = new AtomicReference<AppUser>();
        var roleId = new AtomicReference<UUID>();
        var caseId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "caseadmin@example.com"));
            AppUser a = fixture.createUserWithPassword(tenant, "caseactor@example.com", "long-enough-password");
            actor.set(a);

            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID id = cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
            caseId.set(id);
            // Satisfied so advance() actually succeeds (200) rather than 409 -- the
            // "before" side must be something other than 403 for the revoke to prove
            // anything.
            requirements.satisfy(cases.roadmap(id).stages().get(0).milestones().get(0)
                    .requirements().get(0).id(), null, null);

            // WORKFLOW_VIEW ALL alongside CASE_ADVANCE: advance()'s return value
            // reads the case's pinned Stage under WORKFLOW_VIEW, not case.advance --
            // same invariant CaseEditTest/MilestoneEditTest document.
            roleId.set(roles.createRole("Temporary Advance", "", Map.of(
                    PermissionKeys.CASE_ADVANCE, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL)));
            roles.assignRole(a.getId(), roleId.get());
        });

        String token = tokens.issueAccessToken(actor.get());

        mvc.perform(post("/api/t/case-perm-change/cases/" + caseId.get() + "/advance")
                .header("Authorization", "Bearer " + token))
           .andExpect(status().isOk());

        mvc.perform(as(put("/api/t/case-perm-change/admin/roles/" + roleId.get() + "/grants"), admin.get())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
           .andExpect(status().isNoContent());

        mvc.perform(post("/api/t/case-perm-change/cases/" + caseId.get() + "/advance")
                .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

    /**
     * Authority is resolved per request, so a deactivated user's reads collapse on
     * the very next call -- not fifteen minutes later when their access token
     * happens to expire. AuthorizationService joins app_user on status = 'ACTIVE'.
     */
    @Test
    void aDeactivatedUsersCaseReadsCollapseImmediately() throws Exception {
        UUID tenant = fixture.createTenant("case-deactivated");
        var admin = new AtomicReference<AppUser>();
        var actor = new AtomicReference<AppUser>();
        var caseId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "deactadmin@example.com"));
            AppUser a = fixture.createUserWithPassword(tenant, "deactme@example.com", "long-enough-password");
            actor.set(a);

            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            caseId.set(cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id());

            UUID role = roles.createRole("Case Viewer", "", Map.of(
                    PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(a.getId(), role);
        });

        String token = tokens.issueAccessToken(actor.get());

        mvc.perform(get("/api/t/case-deactivated/cases/" + caseId.get())
                .header("Authorization", "Bearer " + token))
           .andExpect(status().isOk());

        mvc.perform(as(post("/api/t/case-deactivated/admin/users/" + actor.get().getId() + "/deactivate"), admin.get()))
           .andExpect(status().isNoContent());

        mvc.perform(get("/api/t/case-deactivated/cases/" + caseId.get())
                .header("Authorization", "Bearer " + token))
           .andExpect(status().isForbidden());
    }

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
