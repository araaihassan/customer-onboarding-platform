package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test 2 — authenticated, but holding no permission at all. */
class InsufficientPermissionTest extends SecurityTestBase {

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;

    /** ACTIVE and INTERNAL, so the only thing missing is authority. */
    private AppUser userWithNoRoles(String slug) {
        UUID tenant = fixture.createTenant(slug);
        return fixture.createUserWithPassword(tenant, "nobody@example.com", "long-enough-password");
    }

    /** A case.view-only actor: holds enough to read the case, nothing that writes to it. */
    private record CaseViewOnly(AppUser viewer, UUID caseId, UUID milestoneId,
                                UUID requirementId, UUID templateId, UUID versionId) {}

    private CaseViewOnly aCaseWithAViewOnlyActor(String slug) {
        UUID tenant = fixture.createTenant(slug);
        var result = new CaseViewOnly[1];
        fixture.runAs(tenant, () -> {
            var stageRequest = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Stage One", null, false, true, true, null, WriteScope.ANY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
            UUID templateId = journey.templateOf(versionId);
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
            var roadmap = cases.roadmap(caseId);
            UUID milestoneId = roadmap.stages().get(0).milestones().get(0).id();
            UUID requirementId = roadmap.stages().get(0).milestones().get(0).requirements().get(0).id();

            AppUser viewer = fixture.createUserWithPassword(tenant, "viewonly@example.com", "long-enough-password");
            UUID role = roles.createRole("View Only", "", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
            roles.assignRole(viewer.getId(), role);
            result[0] = new CaseViewOnly(viewer, caseId, milestoneId, requirementId, templateId, versionId);
        });
        return result[0];
    }

    /**
     * A user holding only case.view cannot advance, hold, reopen a milestone,
     * waive a requirement, request a forced completion, migrate, or publish a
     * workflow. Seven refusals, each named.
     */
    @Test
    void aCaseViewerCannotAdvance() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-advance");
        mvc.perform(as(post("/api/t/perm-advance/cases/" + s.caseId() + "/advance"), s.viewer()))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotHold() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-hold");
        mvc.perform(as(post("/api/t/perm-hold/cases/" + s.caseId() + "/hold"), s.viewer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotReopenAMilestone() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-reopen");
        mvc.perform(as(post("/api/t/perm-reopen/cases/" + s.caseId() + "/milestones/" + s.milestoneId() + "/reopen"),
                        s.viewer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotWaiveARequirement() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-waive");
        mvc.perform(as(post("/api/t/perm-waive/cases/" + s.caseId() + "/requirements/" + s.requirementId() + "/waive"),
                        s.viewer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotRequestAForcedCompletion() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-force");
        mvc.perform(as(post("/api/t/perm-force/cases/" + s.caseId() + "/milestones/" + s.milestoneId() + "/force-complete"),
                        s.viewer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotMigrate() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-migrate");
        mvc.perform(as(post("/api/t/perm-migrate/cases/migration"), s.viewer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\":\"" + s.versionId() + "\",\"caseIds\":[\"" + s.caseId() + "\"]}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void aCaseViewerCannotPublishAWorkflow() throws Exception {
        CaseViewOnly s = aCaseWithAViewOnlyActor("perm-publish");
        mvc.perform(as(post("/api/t/perm-publish/workflows/" + s.templateId() + "/versions/" + s.versionId() + "/publish"),
                        s.viewer()))
           .andExpect(status().isForbidden());
    }

    @Test
    void creatingACustomerIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-create");
        mvc.perform(as(post("/api/t/perm-create/customers"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void listingUsersIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-users");
        mvc.perform(as(get("/api/t/perm-users/admin/users"), user))
           .andExpect(status().isForbidden());
    }

    @Test
    void creatingARoleIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-roles");
        mvc.perform(as(post("/api/t/perm-roles/admin/roles"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"description\":\"\",\"grants\":{}}"))
           .andExpect(status().isForbidden());
    }

    /**
     * The response must not teach a caller which permission would unlock the
     * endpoint. A body naming customer.create turns every 403 into a map of the
     * authorization model, which is exactly what an attacker enumerating an API
     * wants.
     */
    @Test
    void theResponseNamesNoPermission() throws Exception {
        var user = userWithNoRoles("perm-quiet");

        String body = mvc.perform(as(post("/api/t/perm-quiet/customers"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("customer.create")
                .doesNotContain("customer.view")
                .doesNotContain("RequirePermission")
                .doesNotContain("permission");
    }
}
