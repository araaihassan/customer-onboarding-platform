package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.security.SecurityTestBase;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sub-project 3A, Task 27.5 (inserted plan amendment -- see
 * {@code .superpowers/sdd/2026-09-08-programmes-and-customer-plans/task-27.5-brief.md}):
 * REST-level smoke tests proving {@link PlanRevisionController} wires
 * {@code issue}/{@code get}/{@code listForCase}/{@code decide}/{@code diff}
 * (the last one Task 27's own deliverable, moved here under the same base
 * mapping) into {@link PlanRevisionService} correctly.
 *
 * Business logic for all five methods is already fully proven at the service
 * layer by {@code PlanRevisionTest}/{@code PlanRevisionDiffTest} -- these
 * tests deliberately do NOT re-derive gate-1-&gt;gate-2 ordering, snapshot
 * mechanics, or diff row semantics. They only prove: the HTTP path reaches
 * the right service method (one happy-path assertion each), the same
 * permission gate the service carries is enforced end to end (one 403 smoke
 * test per distinct gate), and a cross-tenant id is a 404, never a 403 or a
 * 500 (one consolidated smoke test across every endpoint).
 */
class PlanRevisionControllerTest extends SecurityTestBase {

    @Autowired ObjectMapper mapper;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowVersionRepository versionRepository;
    @Autowired PlanShapeService planShapeService;
    @Autowired CaseService caseService;
    @Autowired CaseRepository caseRepository;
    @Autowired CaseEngine engine;

    private UUID tenant;
    private String tenantSlug;
    private UUID team;
    private AppUser pm;
    private AppUser am;
    private UUID caseId;

    @BeforeEach
    void seedApprovedCustomerCaseAndTwoNarrowActors() {
        // UUIDv7's high bits are timestamp-derived, so a short prefix substring
        // collides across tests generated moments apart -- the full UUID string
        // (as every other fixture in this codebase already uses for slugs) does
        // not, since its trailing bits are still random.
        tenantSlug = "plan-rev-ctrl-" + Uuid7.generate();
        tenant = fixture.createTenant(tenantSlug);

        pm = fixture.createUserWithPassword(tenant,
                "pm+" + Uuid7.generate() + "@plan-revision-controller.example", "long-enough-password");
        am = fixture.createUserWithPassword(tenant,
                "am+" + Uuid7.generate() + "@plan-revision-controller.example", "long-enough-password");

        fixture.runAs(tenant, () -> {
            team = fixture.createTeam(tenant, "Controller Team " + Uuid7.generate());
            fixture.addToTeam(tenant, pm.getId(), team);
            fixture.addToTeam(tenant, am.getId(), team);

            UUID pmRole = roles.createRole("Controller PM " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PLAN_ISSUE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(pm.getId(), pmRole);

            UUID amRole = roles.createRole("Controller AM " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PLAN_APPROVE_SCHEDULE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(am.getId(), amRole);
        });

        caseId = openApprovedCustomerCase(team);
    }

    @Test
    void issueReachesTheServiceAndCreatesARevision() throws Exception {
        mvc.perform(as(post(base() + "/plan-revisions"), pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"v1\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.revisionNumber").value(1))
           .andExpect(jsonPath("$.status").value("ISSUED"))
           .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void listForCaseReturnsIssuedRevisionsNewestFirst() throws Exception {
        issueViaRest(pm, "first");
        issueViaRest(pm, "second");

        mvc.perform(as(get(base() + "/plan-revisions"), pm))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(2))
           .andExpect(jsonPath("$[0].revisionNumber").value(2))
           .andExpect(jsonPath("$[0].status").value("ISSUED"))
           .andExpect(jsonPath("$[1].revisionNumber").value(1))
           .andExpect(jsonPath("$[1].status").value("SUPERSEDED"));
    }

    @Test
    void getReturnsTheRevisionAndItsItems() throws Exception {
        String revisionId = issueViaRest(pm, "for get");

        mvc.perform(as(get(base() + "/plan-revisions/" + revisionId), pm))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(revisionId))
           .andExpect(jsonPath("$.issueNote").value("for get"));
    }

    /**
     * Uses a SEPARATE, naturally-held case (never forced ACTIVE, unlike {@link
     * #caseId}) so this test can prove the real production effect of an
     * APPROVED decision -- releasing the QA Q22/Q23 hold -- through the REST
     * layer, the same "seed the state directly, no production path needed"
     * split {@code PlanRevisionTest.openHeldCaseOnCustomerTemplate}/{@code
     * openApprovedCustomerCase} already established. {@link #caseId} itself
     * is forced ACTIVE precisely so its milestones are instantiated with real
     * due dates for the other tests' item-count/date assertions; a held
     * case's milestones are NOT instantiated yet (no entered stage), so this
     * test does not assert on the revision's item count, only on the
     * decision and the hold release.
     */
    @Test
    void decideRecordsTheDecisionAndReleasesTheHold() throws Exception {
        UUID heldCaseId = openHeldCustomerCase(team);
        String heldBase = "/api/t/" + tenantSlug + "/cases/" + heldCaseId;

        String created = mvc.perform(as(post(heldBase + "/plan-revisions"), pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"for decide\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String revisionId = JsonPath.read(created, "$.id");

        mvc.perform(as(post(heldBase + "/plan-revisions/" + revisionId + "/decision"), am)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"note\":\"looks good\"}"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("APPROVED"))
           .andExpect(jsonPath("$.decisionNote").value("looks good"));

        var status = new AtomicReference<CaseStatus>();
        fixture.runAs(tenant, () -> status.set(caseRepository.findById(heldCaseId).orElseThrow().getStatus()));
        org.assertj.core.api.Assertions.assertThat(status.get()).isEqualTo(CaseStatus.ACTIVE);
    }

    @Test
    void diffIsReachableUnderTheNewBaseMapping() throws Exception {
        String first = issueViaRest(pm, "diff v1");
        String second = issueViaRest(pm, "diff v2");

        mvc.perform(as(get(base() + "/plan-revisions/" + second + "/diff?against=" + first), pm))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.rows.length()").value(1))
           .andExpect(jsonPath("$.rows[0].changeKind").value("UNCHANGED"));
    }

    /**
     * {@code issue}/{@code listForCase}/{@code get}/{@code diff} all carry the
     * exact same {@code plan.issue} gate {@link PlanRevisionService} already
     * enforces -- {@code am} above holds only {@code plan.approve_schedule},
     * never {@code plan.issue}, so every one of the four must refuse it.
     */
    @Test
    void createStillRequiresPlanIssueButReadsNowAcceptPlanApproveScheduleToo() throws Exception {
        String revisionId = issueViaRest(pm, "for 403 probe");

        // create carries plan.issue alone -- am (only plan.approve_schedule) is
        // still refused here; this half of the original assertion still holds.
        mvc.perform(as(post(base() + "/plan-revisions"), am)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"nope\"}"))
           .andExpect(status().isForbidden());

        // Final whole-branch review finding #4: get/listForCase/diff used to be
        // plan.issue-only too, so am -- the ONE seeded role shape that can
        // actually decide a schedule revision -- got a 403 reading the very
        // revision it is supposed to approve. Widened to accept
        // plan.approve_schedule as well (PlanRevisionService's own javadoc);
        // these three now succeed for am rather than 403, unlike create above.
        mvc.perform(as(get(base() + "/plan-revisions"), am))
           .andExpect(status().isOk());

        mvc.perform(as(get(base() + "/plan-revisions/" + revisionId), am))
           .andExpect(status().isOk());

        mvc.perform(as(get(base() + "/plan-revisions/" + revisionId + "/diff?against=" + revisionId), am))
           .andExpect(status().isOk());
    }

    /** {@code decide} carries {@code plan.approve_schedule} -- {@code pm} holds only {@code plan.issue}. */
    @Test
    void decideAnswers403ForAnActorLackingPlanApproveSchedule() throws Exception {
        String revisionId = issueViaRest(pm, "for decide 403");

        mvc.perform(as(post(base() + "/plan-revisions/" + revisionId + "/decision"), pm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"note\":\"wrong actor\"}"))
           .andExpect(status().isForbidden());
    }

    /**
     * A cross-tenant id is consistently a 404 (CLAUDE.md's own invariant) --
     * never the 403 a scope check alone would produce, nor a 500. {@code am2}
     * holds the same two permissions {@code pm}/{@code am} do, at ALL, inside
     * tenant B, so any non-404 result here would be a real cross-tenant leak,
     * not a permission gap.
     */
    @Test
    void crossTenantIdsAnswer404AcrossEveryEndpoint() throws Exception {
        String revisionId = issueViaRest(pm, "cross-tenant target");

        String tenantBSlug = "plan-rev-ctrl-b-" + Uuid7.generate();
        UUID tenantB = fixture.createTenant(tenantBSlug);
        AppUser inTenantB = fixture.createUserWithPassword(tenantB,
                "actor+" + Uuid7.generate() + "@plan-revision-controller.example", "long-enough-password");
        fixture.runAs(tenantB, () -> {
            UUID role = roles.createRole("Cross Tenant Actor " + Uuid7.generate(), "", Map.of(
                    PermissionKeys.PLAN_ISSUE, Scope.ALL,
                    PermissionKeys.PLAN_APPROVE_SCHEDULE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            roles.assignRole(inTenantB.getId(), role);
        });

        String otherTenantSlugBase = "/api/t/" + tenantBSlug + "/cases/" + caseId;

        mvc.perform(as(post(otherTenantSlugBase + "/plan-revisions"), inTenantB)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"cross\"}"))
           .andExpect(status().isNotFound());

        mvc.perform(as(get(otherTenantSlugBase + "/plan-revisions"), inTenantB))
           .andExpect(status().isNotFound());

        mvc.perform(as(get(otherTenantSlugBase + "/plan-revisions/" + revisionId), inTenantB))
           .andExpect(status().isNotFound());

        mvc.perform(as(post(otherTenantSlugBase + "/plan-revisions/" + revisionId + "/decision"), inTenantB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"APPROVED\",\"note\":\"cross\"}"))
           .andExpect(status().isNotFound());

        mvc.perform(as(get(otherTenantSlugBase + "/plan-revisions/" + revisionId + "/diff?against=" + revisionId),
                        inTenantB))
           .andExpect(status().isNotFound());
    }

    private String base() {
        return "/api/t/" + tenantSlug + "/cases/" + caseId;
    }

    private String issueViaRest(AppUser actor, String note) throws Exception {
        String created = mvc.perform(as(post(base() + "/plan-revisions"), actor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new IssueRevisionRequest(note))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    /**
     * A customer-tier clone (one stage, one portal-visible milestone),
     * published, cased, shape-submitted and APPROVED -- the shared setup
     * {@link #openApprovedCustomerCase}/{@link #openHeldCustomerCase} both
     * build on, mirroring {@code PlanRevisionTest.publishedCustomerClone}'s
     * own shape.
     */
    private UUID caseOnApprovedShapeClone(UUID owningTeamId) {
        var caseIdRef = new AtomicReference<UUID>();
        UUID catalogueTemplateId = workflows.createTemplate("Controller Fixture " + Uuid7.generate(), "").id();
        UUID catalogueDraftId = workflows.createDraft(catalogueTemplateId);
        workflows.replaceDraft(catalogueDraftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up")))))),
                List.of(), 0L));
        publishService.publish(catalogueDraftId);

        UUID customerId = fixture.createCustomer(tenant, "Controller Customer " + Uuid7.generate(),
                null, null, owningTeamId);
        var clone = customerTemplates.clone(catalogueTemplateId,
                new CloneTemplateRequest(customerId, "Controller Clone " + Uuid7.generate()));
        UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
        publishService.publish(cloneVersionId);

        UUID contactId = fixture.createContact(tenant, customerId,
                "sponsor+" + Uuid7.generate() + "@plan-revision-controller.example");

        CaseView view = caseService.create(new CreateCaseRequest(customerId, clone.id(),
                "Controller Case " + Uuid7.generate(), Map.of()));

        planShapeService.submit(cloneVersionId);
        planShapeService.decide(cloneVersionId,
                new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));

        caseIdRef.set(view.id());
        return caseIdRef.get();
    }

    /**
     * {@link #caseOnApprovedShapeClone}, then forced ACTIVE and reconciled
     * directly against the repository/engine -- the same "seed the state
     * directly, no production path needed for this test" pattern {@code
     * PlanRevisionTest.openApprovedCustomerCase} already established. Used
     * by every test except {@link #decideRecordsTheDecisionAndReleasesTheHold},
     * so that a real milestone (with a real due date) exists to snapshot --
     * a case left ON_HOLD (Task 26's natural creation state for a
     * customer-owned template) has no entered stage yet, so {@code issue}
     * would snapshot zero items against it.
     */
    private UUID openApprovedCustomerCase(UUID owningTeamId) {
        var caseRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID id = caseOnApprovedShapeClone(owningTeamId);
            Case c = caseRepository.findById(id).orElseThrow();
            c.setStatus(CaseStatus.ACTIVE);
            c.setHeldAt(null);
            c = caseRepository.saveAndFlush(c);
            engine.reconcile(c);
            caseRef.set(id);
        });
        return caseRef.get();
    }

    /**
     * {@link #caseOnApprovedShapeClone}, left exactly as {@code
     * caseService.create} produces it for a customer-owned template --
     * ON_HOLD (QA Q22/Q23 gate 2) -- so {@link
     * #decideRecordsTheDecisionAndReleasesTheHold} can prove an APPROVED
     * decision through the REST layer genuinely resumes it, the same "seed
     * the state directly, no production path needed" split {@code
     * PlanRevisionTest.openHeldCaseOnCustomerTemplate} already established.
     */
    private UUID openHeldCustomerCase(UUID owningTeamId) {
        var caseRef = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> caseRef.set(caseOnApprovedShapeClone(owningTeamId)));
        return caseRef.get();
    }
}
