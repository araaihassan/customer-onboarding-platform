package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8's own HTTP-level test, in the workflow package rather than security --
 * SecurityTestBase's nine negative tests are the platform-wide catalogue, but this
 * class is about the workflow module's own status contract, which belongs beside the
 * controller it exercises.
 *
 * mvc/fixture/roles are declared fresh here rather than inherited: SecurityTestBase's
 * fields are protected, not public, so a same-named field in this subclass is a
 * separate autowired instance that simply reads better than a qualified
 * {@code super.mvc} at every call site. Only {@link SecurityTestBase#as} is actually
 * reused from the parent.
 */
class WorkflowApiTest extends SecurityTestBase {

    @Autowired MockMvc mvc;
    @Autowired TenantFixture fixture;
    @Autowired RoleService roles;
    @Autowired ObjectMapper mapper;

    private AppUser adminUser(UUID tenant, String email) {
        return fixture.createAdminUser(tenant, email);
    }

    @Test
    void theDefinitionRoundTripsThroughTheApi() throws Exception {
        UUID tenant = fixture.createTenant("wf-api");
        AppUser admin = adminUser(tenant, "wfadmin@example.com");

        String created = mvc.perform(as(post("/api/t/wf-api/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-api/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        mvc.perform(as(get("/api/t/wf-api/workflows/" + templateId + "/versions/" + versionId), admin))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    /**
     * A second draft is a 409, not a 500 from the partial unique index -- and the
     * body names the open draft's versionId, which is what lets a caller resume or
     * discard it instead of being stuck behind a message with nothing to act on.
     */
    @Test
    void aSecondDraftAnswers409() throws Exception {
        UUID tenant = fixture.createTenant("wf-dup-draft");
        AppUser admin = adminUser(tenant, "wfdup@example.com");

        String created = mvc.perform(as(post("/api/t/wf-dup-draft/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-dup-draft/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        mvc.perform(as(post("/api/t/wf-dup-draft/workflows/" + templateId + "/versions"), admin))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.versionId").value(versionId.toString()));
    }

    /** Publish failures are a 422 carrying every problem, so the builder can list them. */
    @Test
    void anInvalidPublishAnswers422WithEveryProblem() throws Exception {
        UUID tenant = fixture.createTenant("wf-invalid-publish");
        AppUser admin = adminUser(tenant, "wfinvalid@example.com");

        String created = mvc.perform(as(post("/api/t/wf-invalid-publish/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-invalid-publish/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        // s1 has no milestones (rule 5: an empty stage) and s3 carries a branch rule
        // that targets s1 -- backward, since s1's ordinal is behind s3's (rule 2). The
        // branch condition names an attribute this draft DOES declare, so only those
        // two rules fire and the count below stays a deliberate two, not an
        // accidental third from an undeclared reference.
        var s1 = WorkflowFixtures.stage("s1", "Stage One", List.of());
        var s2 = WorkflowFixtures.stage("s2", "Stage Two", List.of(
                WorkflowFixtures.milestone("m2", "Milestone Two", 1, List.of(),
                        List.of(WorkflowFixtures.manual("Do it")))));
        var s3 = WorkflowFixtures.stage("s3", "Stage Three", List.of(
                WorkflowFixtures.milestone("m3", "Milestone Three", 1, List.of(),
                        List.of(WorkflowFixtures.manual("Do it")))));
        var attributes = List.of(new AttributeRequest("flag", "Flag", AttributeType.STRING, false, null));
        WorkflowDefinitionRequest base = new WorkflowDefinitionRequest(List.of(s1, s2, s3), attributes, 0L);
        WorkflowDefinitionRequest broken = WorkflowFixtures.withBranch(base, "s3", "flag", "x", "s1");

        mvc.perform(as(put("/api/t/wf-invalid-publish/workflows/" + templateId + "/versions/" + versionId), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(broken)))
           .andExpect(status().isOk());

        mvc.perform(as(post("/api/t/wf-invalid-publish/workflows/" + templateId + "/versions/" + versionId + "/publish"), admin))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.problems.length()").value(greaterThanOrEqualTo(2)));
    }

    /** A stale draft write is a 409, so the builder can say "someone else saved first". */
    @Test
    void aStaleDraftWriteAnswers409() throws Exception {
        UUID tenant = fixture.createTenant("wf-stale");
        AppUser admin = adminUser(tenant, "wfstale@example.com");

        String created = mvc.perform(as(post("/api/t/wf-stale/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-stale/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        // Both writes carry lockVersion 0, the fresh draft's own starting value. The
        // first succeeds and advances it to 1; the second is the "concurrent" writer
        // who never saw that -- it is stale precisely because it still sends 0.
        String body = mapper.writeValueAsString(WorkflowFixtures.twoStages());

        mvc.perform(as(put("/api/t/wf-stale/workflows/" + templateId + "/versions/" + versionId), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isOk());

        mvc.perform(as(put("/api/t/wf-stale/workflows/" + templateId + "/versions/" + versionId), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
           .andExpect(status().isConflict());
    }

    /** Task 1's derived sweep covers these automatically; this asserts the 403 path. */
    @Test
    void aWorkflowViewerCannotPublish() throws Exception {
        UUID tenant = fixture.createTenant("wf-viewer");
        AppUser admin = adminUser(tenant, "wfviewadmin@example.com");
        var viewer = new AtomicReference<AppUser>();

        String created = mvc.perform(as(post("/api/t/wf-viewer/workflows"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Standard\",\"description\":\"\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID templateId = UUID.fromString(JsonPath.read(created, "$.id"));

        String draft = mvc.perform(as(post("/api/t/wf-viewer/workflows/" + templateId + "/versions"), admin))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID versionId = UUID.fromString(JsonPath.read(draft, "$.versionId"));

        fixture.runAs(tenant, () -> {
            AppUser v = fixture.createUserWithPassword(tenant, "wfviewer@example.com", "long-enough-password");
            viewer.set(v);
            UUID role = roles.createRole("Workflow Viewer", "",
                    Map.of(PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(v.getId(), role);
        });

        mvc.perform(as(post("/api/t/wf-viewer/workflows/" + templateId + "/versions/" + versionId + "/publish"), viewer.get()))
           .andExpect(status().isForbidden());
    }
}
