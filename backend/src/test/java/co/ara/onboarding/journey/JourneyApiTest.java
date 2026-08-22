package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 20's own HTTP-level test, in the journey package rather than security --
 * WorkflowApiTest's own note explains why. Workflow-definition setup (templates,
 * versions, publishing) goes through JourneyFixtures directly, the same shortcut
 * HoldTest/MilestoneEditTest already take; only the case/milestone/approval/
 * migration behaviour under test goes through MockMvc.
 */
class JourneyApiTest extends SecurityTestBase {

    @Autowired MockMvc mvc;
    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired RoleService roles;
    @Autowired ObjectMapper mapper;

    @Test
    void aCaseCanBeOpenedAndReadBack() throws Exception {
        UUID tenant = fixture.createTenant("api-open");
        AppUser admin = fixture.createAdminUser(tenant, "open@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplate();
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });

        String created = mvc.perform(as(post("/api/t/api-open/cases"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CreateCaseRequest(customerId[0], templateId[0], Map.of()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID caseId = UUID.fromString(JsonPath.read(created, "$.id"));

        mvc.perform(as(get("/api/t/api-open/cases/" + caseId), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(caseId.toString()))
           .andExpect(jsonPath("$.customerId").value(customerId[0].toString()));
    }

    /** The switcher's payload: several cases for one customer, newest first. */
    @Test
    void casesForACustomerAreListedNewestFirst() throws Exception {
        UUID tenant = fixture.createTenant("api-list");
        AppUser admin = fixture.createAdminUser(tenant, "list@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplate();
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });

        String first = createCase(admin, "api-list", customerId[0], templateId[0]);
        String second = createCase(admin, "api-list", customerId[0], templateId[0]);

        mvc.perform(as(get("/api/t/api-list/customers/" + customerId[0] + "/cases"), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].id").value(second))
           .andExpect(jsonPath("$[1].id").value(first));
    }

    /** Roadmap is its own call so the header renders before the stage graph arrives. */
    @Test
    void theRoadmapIsASeparateEndpoint() throws Exception {
        UUID tenant = fixture.createTenant("api-roadmap");
        AppUser admin = fixture.createAdminUser(tenant, "roadmap@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplate();
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });
        String caseId = createCase(admin, "api-roadmap", customerId[0], templateId[0]);

        mvc.perform(as(get("/api/t/api-roadmap/cases/" + caseId), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.stages").doesNotExist());

        mvc.perform(as(get("/api/t/api-roadmap/cases/" + caseId + "/roadmap"), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.stages.length()").value(1))
           .andExpect(jsonPath("$.stages[0].milestones.length()").value(1));
    }

    @Test
    void anotherTenantsCaseIdAnswers404() throws Exception {
        UUID tenantA = fixture.createTenant("api-cross-a");
        UUID tenantB = fixture.createTenant("api-cross-b");
        fixture.createAdminUser(tenantA, "crossa@example.com");
        AppUser adminB = fixture.createAdminUser(tenantB, "crossb@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            templateId[0] = journey.publishedTemplate();
            customerId[0] = fixture.createCustomer(tenantA, "Acme", null, null, null);
        });
        AppUser adminA = fixture.createAdminUser(tenantA, "crossa2@example.com");
        String caseId = createCase(adminA, "api-cross-a", customerId[0], templateId[0]);

        mvc.perform(as(get("/api/t/api-cross-b/cases/" + caseId), adminB))
           .andExpect(status().isNotFound());
    }

    @Test
    void anAttributeValidationFailureAnswers422WithEveryProblem() throws Exception {
        UUID tenant = fixture.createTenant("api-attrs");
        AppUser admin = fixture.createAdminUser(tenant, "attrs@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplateWithSegmentAttribute();
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });

        mvc.perform(as(post("/api/t/api-attrs/cases"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CreateCaseRequest(customerId[0], templateId[0], Map.of()))))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.problems.length()").value(1));
    }

    @Test
    void advancingAStageThatIsNotExitableAnswers409() throws Exception {
        UUID tenant = fixture.createTenant("api-advance");
        AppUser admin = fixture.createAdminUser(tenant, "advance@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplate();   // one unsatisfied requirement
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });
        String caseId = createCase(admin, "api-advance", customerId[0], templateId[0]);

        mvc.perform(as(post("/api/t/api-advance/cases/" + caseId + "/advance"), admin))
           .andExpect(status().isConflict());
    }

    /**
     * 403, not 404: the caller may see this record, so hiding it would be a lie the
     * UI cannot recover from -- they are looking at the milestone on screen.
     */
    @Test
    void aWriteScopeRefusalAnswers403() throws Exception {
        UUID tenant = fixture.createTenant("api-writescope");
        var versionId = new UUID[1];
        var templateId = new UUID[1];
        var caseOwner = new AppUser[1];
        var actor = new AppUser[1];
        fixture.runAs(tenant, () -> {
            var stageRequest = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Stage One", null, false, true, true, null, WriteScope.OWNER_ONLY, null,
                    null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            versionId[0] = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
            templateId[0] = journey.templateOf(versionId[0]);

            caseOwner[0] = fixture.createUserWithPassword(tenant, "owner@example.com", "long-enough-password");
            actor[0] = fixture.createUserWithPassword(tenant, "notowner@example.com", "long-enough-password");
            // WORKFLOW_VIEW ALL: MilestoneService.update's own stageOf(m) lookup
            // resolves the milestone's Stage under WORKFLOW_VIEW before write_scope
            // ever runs -- the same invariant CaseEditTest/MilestoneEditTest document.
            UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.MILESTONE_EDIT, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                    PermissionKeys.CASE_VIEW, Scope.ALL));
            roles.assignRole(actor[0].getId(), role);
        });

        AppUser admin = fixture.createAdminUser(tenant, "writescopeadmin@example.com");
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> customerId[0] = fixture.createCustomer(
                tenant, "Acme WriteScope", caseOwner[0].getId(), null, null));
        String caseId = createCase(admin, "api-writescope", customerId[0], templateId[0]);

        String roadmap = mvc.perform(as(get("/api/t/api-writescope/cases/" + caseId + "/roadmap"), admin))
                .andReturn().getResponse().getContentAsString();
        String milestoneId = JsonPath.read(roadmap, "$.stages[0].milestones[0].id");

        mvc.perform(as(put("/api/t/api-writescope/cases/" + caseId + "/milestones/" + milestoneId), actor[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dueDate\":\"2030-01-01\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void participantRemovalUsesPostRemoveRatherThanDelete() throws Exception {
        UUID tenant = fixture.createTenant("api-participant");
        AppUser admin = fixture.createAdminUser(tenant, "participant@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        var newUserId = new UUID[1];
        fixture.runAs(tenant, () -> {
            templateId[0] = journey.publishedTemplate();
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
            newUserId[0] = fixture.createUser(tenant, "participant2@example.com");
        });
        String caseId = createCase(admin, "api-participant", customerId[0], templateId[0]);

        mvc.perform(as(post("/api/t/api-participant/cases/" + caseId + "/participants"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + newUserId[0] + "\",\"relationship\":\"PARTICIPANT\"}"))
           .andExpect(status().isNoContent());

        mvc.perform(as(post("/api/t/api-participant/cases/" + caseId
                        + "/participants/" + newUserId[0] + "/remove"), admin))
           .andExpect(status().isNoContent());
    }

    /** Two decide paths, each with its own gate: a stage-exit approval cannot be decided as a force-completion. */
    @Test
    void theTwoApprovalDecideEndpointsAreNotInterchangeable() throws Exception {
        UUID tenant = fixture.createTenant("api-decide");
        AppUser admin = fixture.createAdminUser(tenant, "decide@example.com");
        var templateId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            var approvalStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Stage One", null, true, true, true, null, WriteScope.ANY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(approvalStage), List.of(), 0L));
            templateId[0] = journey.templateOf(versionId);
            customerId[0] = fixture.createCustomer(tenant, "Acme", null, null, null);
        });
        String caseId = createCase(admin, "api-decide", customerId[0], templateId[0]);

        String roadmap = mvc.perform(as(get("/api/t/api-decide/cases/" + caseId + "/roadmap"), admin))
                .andReturn().getResponse().getContentAsString();
        String milestoneId = JsonPath.read(roadmap, "$.stages[0].milestones[0].id");
        String requirementId = JsonPath.read(roadmap, "$.stages[0].milestones[0].requirements[0].id");

        mvc.perform(as(post("/api/t/api-decide/cases/" + caseId + "/requirements/" + requirementId + "/satisfy"), admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
           .andExpect(status().isOk());

        String approvals = mvc.perform(as(get("/api/t/api-decide/cases/" + caseId + "/approvals"), admin))
                .andReturn().getResponse().getContentAsString();
        String approvalId = JsonPath.read(approvals, "$[0].id");

        mvc.perform(as(post("/api/t/api-decide/cases/" + caseId + "/force-requests/" + approvalId + "/decide"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"note\":\"wrong endpoint\"}"))
           .andExpect(status().isConflict());
    }

    private String createCase(AppUser admin, String tenantSlug, UUID customerId, UUID templateId) throws Exception {
        String created = mvc.perform(as(post("/api/t/" + tenantSlug + "/cases"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new CreateCaseRequest(customerId, templateId, Map.of()))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }
}
