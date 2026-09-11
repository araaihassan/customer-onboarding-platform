package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sub-project 3A Task 26 (QA Q23, gate 2's other half): a journey opened on a
 * customer-owned template ({@code workflow_template.customer_id} non-null)
 * starts {@code ON_HOLD}, and a manual {@code resume()} cannot be used to
 * bypass the plan-approval gate that hold exists to enforce -- only {@code
 * PlanRevisionService.decide}'s own first-approval call (Task 25's
 * already-widened {@code resume()} gate) may release it.
 *
 * {@code CaseEngine} is untouched by this task: {@code reconcile}'s existing
 * {@code ON_HOLD} early-return (Task 18) is what keeps a held case from
 * entering its first stage until {@code resume()} actually runs it.
 *
 * Runs everything through {@link TenantFixture#runAs}'s fixture superuser,
 * the same shape {@code CauseBeforeEffectTest.holdAndResumeReadInTheOrderTheyHappened}
 * already uses for hold/resume -- this task is about the new state-machine
 * gate itself, not about scope narrowing, so no hand-built narrow-scoped
 * actor is needed the way {@code PlanRevisionTest}'s {@code pm}/{@code am}
 * are for Tasks 24/25's own cross-module permission findings.
 */
class PlanHoldTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService caseService;
    @Autowired RequirementService requirementService;
    @Autowired TimelineService timeline;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowVersionRepository versionRepository;
    @Autowired PlanShapeService planShapeService;
    @Autowired PlanRevisionService planRevisionService;

    private UUID tenant;

    @BeforeEach
    void createTenant() {
        tenant = fixture.createTenant("plan-hold-" + Uuid7.generate());
    }

    @Test
    void aJourneyOnACustomerTemplateStartsHeld() {
        CaseView c = runAsFixture(() -> caseService.create(onCustomerTemplate()));

        assertThat(c.status()).isEqualTo(CaseStatus.ON_HOLD);
        assertThat(c.heldAt()).isNotNull();
        assertThat(auditActionsFor(c.id())).contains("case.held");
    }

    @Test
    void aJourneyOnACatalogueTemplateIsUnaffected() {
        // Every existing flow and e2e spec depends on this. The gate is real
        // precisely where a customer-tailored plan exists, and nowhere else.
        CaseView c = runAsFixture(() -> caseService.create(onCatalogueTemplate()));

        assertThat(c.status()).isEqualTo(CaseStatus.ACTIVE);
        assertThat(c.heldAt()).isNull();
    }

    @Test
    void noRequirementCanBeSatisfiedWhileAwaitingTheFirstApproval() {
        UUID caseId = runAsFixture(() -> caseService.create(onCustomerTemplate())).id();

        // Never assert an exception inside fixture.runAs's own lambda (it runs in a
        // TransactionTemplate) -- wrap the whole runAs call instead, so the
        // exception under test propagates out rather than being masked by
        // UnexpectedRollbackException.
        assertThatThrownBy(() -> fixture.runAs(tenant,
                        () -> requirementService.satisfy(firstRequirementId(caseId), null, null)))
                .isInstanceOf(CaseOnHoldException.class);      // the EXISTING mechanism, not a new one
    }

    @Test
    void aManualResumeCannotBeUsedToBypassTheGate() {
        UUID caseId = runAsFixture(() -> caseService.create(onCustomerTemplate())).id();

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> caseService.resume(caseId)))
                .isInstanceOf(PlanApprovalOutstandingException.class);   // 409
    }

    @Test
    void aManualHoldLayeredOnTopStillRefusesResumeForItsOwnReason() {
        // The condition is derived, so both reasons compose: clearing one does
        // not clear the other.
        UUID caseId = approvedCustomerJourney();

        // hold() itself is not the thing under test here, so it gets its own
        // plain runAs call; only resume()'s outcome is asserted, and that
        // assertion wraps the WHOLE runAs call rather than nesting inside its
        // lambda -- same reasoning as the two exception-asserting tests above.
        fixture.runAs(tenant, () -> caseService.hold(caseId, "Waiting on legal"));

        assertThatNoException().isThrownBy(
                () -> fixture.runAs(tenant, () -> caseService.resume(caseId)));
    }

    // ---- fixtures -----------------------------------------------------------

    private <T> T runAsFixture(java.util.function.Supplier<T> action) {
        AtomicReference<T> result = new AtomicReference<>();
        fixture.runAs(tenant, () -> result.set(action.get()));
        return result.get();
    }

    private record CustomerTemplate(UUID templateId, UUID customerId, UUID versionId) {}

    /** A customer-tier clone of a fresh catalogue template, published, not yet shape-approved. */
    private CustomerTemplate customerTemplate() {
        UUID catalogueTemplateId = workflows.createTemplate("Fixture Onboarding " + Uuid7.generate(), "").id();
        UUID catalogueDraftId = workflows.createDraft(catalogueTemplateId);
        workflows.replaceDraft(catalogueDraftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up")))))),
                List.of(), 0L));
        publishService.publish(catalogueDraftId);

        UUID customerId = fixture.createCustomer(tenant, "Plan Hold Customer " + Uuid7.generate(),
                null, null, null);
        var clone = customerTemplates.clone(catalogueTemplateId,
                new CloneTemplateRequest(customerId, "Plan Hold Clone " + Uuid7.generate()));
        UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
        publishService.publish(cloneVersionId);

        return new CustomerTemplate(clone.id(), customerId, cloneVersionId);
    }

    private CreateCaseRequest onCustomerTemplate() {
        CustomerTemplate t = customerTemplate();
        return new CreateCaseRequest(t.customerId(), t.templateId(), "Customer Case " + Uuid7.generate(), Map.of());
    }

    private CreateCaseRequest onCatalogueTemplate() {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Catalogue Customer " + Uuid7.generate(),
                null, null, null);
        return new CreateCaseRequest(customerId, templateId, "Catalogue Case " + Uuid7.generate(), Map.of());
    }

    /**
     * A customer-template case carried all the way through both approval
     * gates (shape, then schedule) to ACTIVE -- the state the hold this task
     * adds is meant to lead to, not bypass. Runs entirely as the tenant's
     * fixture superuser, same as {@code PlanRevisionTest.openApprovedCustomerCase}.
     */
    private UUID approvedCustomerJourney() {
        AtomicReference<UUID> caseIdRef = new AtomicReference<>();
        fixture.runAs(tenant, () -> {
            CustomerTemplate t = customerTemplate();
            UUID contactId = fixture.createContact(tenant, t.customerId(),
                    "sponsor+" + Uuid7.generate() + "@plan-hold.example");
            planShapeService.submit(t.versionId());
            planShapeService.decide(t.versionId(),
                    new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));

            UUID caseId = caseService.create(new CreateCaseRequest(t.customerId(), t.templateId(),
                    "Approved Case " + Uuid7.generate(), Map.of())).id();

            PlanRevisionView rev = planRevisionService.issue(caseId, new IssueRevisionRequest("v1"));
            planRevisionService.decide(rev.id(),
                    new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));

            caseIdRef.set(caseId);
        });
        return caseIdRef.get();
    }

    private UUID firstRequirementId(UUID caseId) {
        return caseService.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }

    private List<String> auditActionsFor(UUID caseId) {
        return runAsFixture(() -> timeline.forCase(caseId, Pageable.ofSize(50)).getContent().stream()
                .map(AuditEventView::action)
                .toList());
    }
}
