package co.ara.onboarding.security;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.journey.ApprovalService;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Test 1's own sub-project 2 sweep: every id the journey/workflow surface accepts
 * from a URL or a body, fed a foreign tenant's value.
 */
class CaseIsolationTest extends SecurityTestBase {

    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired ApprovalService approvals;

    /**
     * Every id this sub-project accepts from a URL or a body, fed a foreign
     * tenant's value. Derived from the endpoint table (spec 7.2) rather than a
     * hand-picked sample, because a hand-picked sample is how DirectApiAccessTest
     * ended up eleven endpoints short.
     *
     * .../milestones/{mid}/complete is not in this list: Task 20 found no service
     * method backs it (see that task's plan amendment) and it was never built.
     */
    @Test
    void everyForeignIdAnswers404() throws Exception {
        UUID tenantA = fixture.createTenant("iso-a");
        UUID tenantB = fixture.createTenant("iso-b");
        FullCase b = seedFullCase(tenantB);
        AppUser aAdmin = fixture.createAdminUser(tenantA, "aadmin@example.com");

        record Probe(String description, MockHttpServletRequestBuilder request) {}
        List<Probe> probes = List.of(
                new Probe("case", get("/api/t/iso-a/cases/" + b.caseId())),
                new Probe("roadmap", get("/api/t/iso-a/cases/" + b.caseId() + "/roadmap")),
                new Probe("timeline", get("/api/t/iso-a/cases/" + b.caseId() + "/timeline")),
                new Probe("advance", post("/api/t/iso-a/cases/" + b.caseId() + "/advance")),
                new Probe("hold", post("/api/t/iso-a/cases/" + b.caseId() + "/hold")
                        .content("{\"reason\":\"x\"}")),
                new Probe("participants", get("/api/t/iso-a/cases/" + b.caseId() + "/participants")),
                new Probe("milestone", put("/api/t/iso-a/cases/" + b.caseId() + "/milestones/" + b.milestoneId())
                        .content("{}")),
                new Probe("reopen", post("/api/t/iso-a/cases/" + b.caseId() + "/milestones/" + b.milestoneId() + "/reopen")
                        .content("{\"reason\":\"x\"}")),
                new Probe("force-complete", post("/api/t/iso-a/cases/" + b.caseId() + "/milestones/" + b.milestoneId() + "/force-complete")
                        .content("{\"reason\":\"x\"}")),
                new Probe("satisfy", post("/api/t/iso-a/cases/" + b.caseId() + "/requirements/" + b.requirementId() + "/satisfy")
                        .content("{}")),
                new Probe("approvals", get("/api/t/iso-a/cases/" + b.caseId() + "/approvals")),
                new Probe("stage-approval-decide", post("/api/t/iso-a/cases/" + b.caseId() + "/stage-approvals/" + b.approvalId() + "/decide")
                        .content("{\"approve\":true}")),
                new Probe("definition", get("/api/t/iso-a/workflows/" + b.templateId() + "/versions/" + b.versionId())),
                new Probe("publish", post("/api/t/iso-a/workflows/" + b.templateId() + "/versions/" + b.versionId() + "/publish")),
                new Probe("migration-preview", get("/api/t/iso-a/cases/migration?versionId=" + b.versionId())),
                new Probe("migration-migrate", post("/api/t/iso-a/cases/migration")
                        .content("{\"versionId\":\"" + b.versionId() + "\",\"caseIds\":[\"" + b.caseId() + "\"]}")));

        for (Probe probe : probes) {
            mvc.perform(as(probe.request().contentType(MediaType.APPLICATION_JSON), aAdmin))
               .andExpect(status().isNotFound());     // never 200, never 403, never 500
        }
    }

    /**
     * The oracle, one level deeper: creating a case names a customer in the body.
     * A foreign customer id must answer exactly what an invented one answers.
     */
    @Test
    void aForeignCustomerIdAndAnInventedOneAreIndistinguishable() throws Exception {
        UUID tenantA = fixture.createTenant("iso-oracle-a");
        UUID tenantB = fixture.createTenant("iso-oracle-b");
        AppUser aAdmin = fixture.createAdminUser(tenantA, "oraclea@example.com");
        var templateId = new UUID[1];
        var foreignCustomerId = new UUID[1];
        fixture.runAs(tenantA, () -> templateId[0] = journey.publishedTemplate());
        fixture.runAs(tenantB, () -> foreignCustomerId[0] = fixture.createCustomer(
                tenantB, "Foreign", null, null, null));

        int foreign = statusOfCreateCase(aAdmin, foreignCustomerId[0], templateId[0]);
        int invented = statusOfCreateCase(aAdmin, Uuid7.generate(), templateId[0]);
        assertThat(foreign).isEqualTo(invented).isEqualTo(404);
    }

    private int statusOfCreateCase(AppUser admin, UUID customerId, UUID templateId) throws Exception {
        return mvc.perform(as(post("/api/t/iso-oracle-a/cases"), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"customerId\":\"" + customerId + "\",\"templateId\":\"" + templateId
                                + "\",\"attributes\":{}}"))
                .andReturn().getResponse().getStatus();
    }

    private record FullCase(UUID caseId, UUID milestoneId, UUID requirementId, UUID approvalId,
                            UUID templateId, UUID versionId) {}

    /** A case, milestone, requirement and a PENDING STAGE_EXIT approval, all on one tenant. */
    private FullCase seedFullCase(UUID tenant) {
        var result = new FullCase[1];
        fixture.runAs(tenant, () -> {
            var stageRequest = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Stage One", null, true, true, true, null, WriteScope.ANY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
            UUID templateId = journey.templateOf(versionId);
            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();

            var roadmap = cases.roadmap(caseId);
            UUID milestoneId = roadmap.stages().get(0).milestones().get(0).id();
            UUID requirementId = roadmap.stages().get(0).milestones().get(0).requirements().get(0).id();

            requirements.satisfy(requirementId, null, null);   // parks a PENDING STAGE_EXIT approval
            UUID approvalId = approvals.listForCase(caseId).get(0).id();

            result[0] = new FullCase(caseId, milestoneId, requirementId, approvalId, templateId, versionId);
        });
        return result[0];
    }
}
