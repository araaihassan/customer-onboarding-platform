package co.ara.onboarding.journey;

import co.ara.onboarding.agreement.AgreementDetailView;
import co.ara.onboarding.agreement.AgreementRepository;
import co.ara.onboarding.agreement.AgreementReviewService;
import co.ara.onboarding.agreement.AgreementService;
import co.ara.onboarding.agreement.AgreementSignatoryView;
import co.ara.onboarding.agreement.AgreementSignatureService;
import co.ara.onboarding.agreement.AgreementTestSupport;
import co.ara.onboarding.agreement.PatchAgreementRequest;
import co.ara.onboarding.agreement.RecordSignatureRequest;
import co.ara.onboarding.agreement.ReplaceSignatoriesRequest;
import co.ara.onboarding.agreement.ReviewAgreementRequest;
import co.ara.onboarding.agreement.ReviewDecision;
import co.ara.onboarding.agreement.SignatoryKind;
import co.ara.onboarding.agreement.SignatoryRequest;
import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.CreateDocumentRequestRequest;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRequestRepository;
import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.TaskRepository;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.TaskStatus;
import co.ara.onboarding.task.TaskStatusRequest;
import co.ara.onboarding.workflow.AgreementRecordMode;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.document;
import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static co.ara.onboarding.workflow.WorkflowFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action's own audit event must be recorded BEFORE the events describing
 * what that action triggered. AuditRecorder stamps occurredAt from the clock,
 * so call order IS timeline order -- see its javadoc for the full rule.
 *
 * Every one of these assertions failed before the fix: nine call sites across
 * five journey services recorded their cause after engine.reconcile(), so a
 * newest-first timeline showed each cause sitting above its own effects. The
 * user's report was "milestones completed before the case is assigned to a
 * user, which is wrong" -- read off the screen, because no test looked at the
 * relative order of two DIFFERENT actions.
 *
 * These assert on ORDER ONLY, never on the number of events, so a sub-project
 * that adds a new event to any of these paths does not have to edit this file.
 */
class CauseBeforeEffectTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired CaseRepository caseRepository;
    @Autowired RequirementService requirements;
    @Autowired TimelineService timeline;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired PlanRevisionService planRevisionService;
    @Autowired PlanShapeService planShapeService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowVersionRepository versionRepository;
    @Autowired DocumentService documents;
    @Autowired DocumentRequestService documentRequestService;
    @Autowired DocumentRequestRepository documentRequests;
    @Autowired AgreementTestSupport agreementSupport;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService agreementReviewService;
    @Autowired AgreementSignatureService agreementSignatureService;
    @Autowired AgreementRepository agreementRepository;

    /** A minimal, real PDF magic prefix -- the identical fixture DocumentServiceTest already uses. */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Test
    void creatingACaseIsRecordedBeforeTheStageEntryAndMilestonesItCauses() {
        UUID tenant = fixture.createTenant("cbe-create");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);

            // Oldest-first: the case is opened, and only then does anything
            // happen inside it.
            assertThat(chronological(caseId))
                    .startsWith("case.created")
                    .containsSubsequence("case.created", "case.stage_entered");
        });
    }

    @Test
    void satisfyingARequirementIsRecordedBeforeTheMilestoneItCompletes() {
        UUID tenant = fixture.createTenant("cbe-satisfy");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);

            // The completion is a CONSEQUENCE of the satisfaction, so it cannot
            // precede it. Before the fix these were the other way round.
            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "milestone.completed");
        });
    }

    @Test
    void aCompletedCaseIsRecordedAfterTheRequirementThatCompletedIt() {
        UUID tenant = fixture.createTenant("cbe-complete");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);   // completes the case

            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "case.completed")
                    .endsWith("case.completed");
        });
    }

    /**
     * Hold and resume are the ordering case that does NOT go through a
     * reconcile-writes-effects path in this fixture, so it is here as the
     * control: it should have read correctly before the fix and still does.
     */
    @Test
    void holdAndResumeReadInTheOrderTheyHappened() {
        UUID tenant = fixture.createTenant("cbe-resume");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            cases.hold(caseId, "pausing");
            cases.resume(caseId);

            assertThat(chronological(caseId))
                    .containsSubsequence("case.created", "case.held", "case.resumed");
        });
    }

    /**
     * Task 25: closes the task.created gap AuditActions' own comment named as
     * still open. TaskInstantiation runs strictly between CaseService.create's
     * own CASE_CREATED record and engine.reconcile (see that call site's
     * comment) -- so a case whose workflow declares a TASK-kind requirement
     * must show its instantiated task's own event after the case's, never
     * before it.
     */
    @Test
    void taskCreationIsRecordedBeforeTheEventsItCauses() {
        UUID tenant = fixture.createTenant("cbe-task-created");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithATaskRequirement(tenant);

            assertThat(chronological(caseId))
                    .containsSubsequence("case.created", "task.created");
        });
    }

    /**
     * The full causal chain sub-project 3 introduces, asserted end to end:
     * TaskService.changeStatus records TASK_STATUS_CHANGED before calling the
     * already-gated RequirementService.satisfy, which itself records
     * requirement.satisfied before the reconcile that completes the milestone
     * -- sub-project 2's own already-proven ordering, chained onto a new
     * caller rather than reimplemented.
     */
    @Test
    void completingATaskIsRecordedBeforeTheRequirementItSatisfies() {
        UUID tenant = fixture.createTenant("cbe-task-complete");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithATaskRequirement(tenant);
            UUID taskId = taskRepository.findByCaseId(caseId).get(0).getId();

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));

            assertThat(chronological(caseId))
                    .containsSubsequence("task.status_changed", "requirement.satisfied",
                            "milestone.completed");
        });
    }

    /**
     * Sub-project 3A Task 25 (QA Q22/Q23 gate 2): {@code
     * PlanRevisionService.decide}'s own {@code plan.revision_decided} record
     * must precede {@code case.resumed} -- the event {@code CaseService.resume}
     * itself records -- on a case's first-ever approved schedule revision. This
     * is the guard whose actual subject this ordering is, per this task's own
     * brief: NOT a {@code PlanRevisionTest} concern, this file's.
     */
    @Test
    void theRevisionDecisionIsRecordedBeforeTheResumeItCauses() {
        UUID tenant = fixture.createTenant("cbe-plan-revision");
        fixture.runAs(tenant, () -> {
            UUID caseId = openHeldCaseOnApprovedCustomerTemplate(tenant);

            PlanRevisionView rev = planRevisionService.issue(caseId, new IssueRevisionRequest("v1"));
            planRevisionService.decide(rev.id(),
                    new DecidePlanRequest(PlanDecision.APPROVED, "Approved", null));

            assertThat(chronological(caseId))
                    .containsSubsequence("plan.revision_decided", "case.resumed");
        });
    }

    /**
     * Sub-project 4 Task 29: the document.* family's own causal chain,
     * chained onto the existing satisfy->reconcile ordering exactly the way
     * completingATaskIsRecordedBeforeTheRequirementItSatisfies chains
     * TaskService.changeStatus onto it above -- upload a document, then
     * fulfil the DOCUMENT-kind requirement's own auto-instantiated (Task 24)
     * document_request with it, which (requiresReview defaulting to false,
     * DocumentInstantiationTest's own confirmed default) calls the existing
     * gated RequirementService.satisfy exactly once, completing the
     * milestone. document.request_fulfilled and document.requested are
     * deliberately not asserted on here -- only the three actions this
     * test's own name is about.
     */
    @Test
    void uploadingADocumentIsRecordedBeforeTheRequirementItSatisfies() {
        UUID tenant = fixture.createTenant("cbe-document-upload");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithADocumentRequirement(tenant);
            UUID requestId = documentRequests.findByCaseId(caseId).get(0).getId();

            UUID documentId = documents.upload(caseId,
                    new CreateDocumentRequest("Provide NDA", DocumentCategory.NDA,
                            VisibilityTier.COMPANY_SHARED, null, null, null, null),
                    new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id();

            documentRequestService.fulfil(requestId, documentId);

            assertThat(chronological(caseId))
                    .containsSubsequence("document.uploaded", "requirement.satisfied", "milestone.completed");
        });
    }

    /** Sub-project 6 Task 20: a reminder cannot precede the request it nudges. */
    @Test
    void aReminderReadsAfterTheRequestItRemindsAbout() {
        UUID tenant = fixture.createTenant("cbe-document-remind");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            UUID customerId = caseRepository.findById(caseId).orElseThrow().getCustomerId();
            UUID contactId = fixture.createContact(tenant, customerId, "remind@cbe-document-remind.example");
            UUID requestId = documentRequestService.create(caseId, new CreateDocumentRequestRequest(
                    DocumentCategory.OTHER, null, null, false, contactId)).id();

            documentRequestService.remind(requestId);

            assertThat(chronological(caseId)).containsSubsequence("document.requested", "document_request.reminded");
        });
    }

    /**
     * Sub-project 5 Task 18: the agreement.* family's causal chain.
     * AgreementSignatureService.record writes agreement.signed BEFORE calling
     * the gated RequirementService.satisfy, which records requirement.satisfied
     * ahead of the reconcile that completes the milestone. The agreement is
     * driven through the real lifecycle (patch, signatories, submit, review by
     * a second user, send, record) on a STRUCTURED_ONLY SIGNATURE requirement
     * with one internal signatory, so no file is involved.
     */
    @Test
    void signingAnAgreementIsRecordedBeforeTheRequirementItSatisfies() {
        UUID tenant = fixture.createTenant("cbe-agreement-signed");
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        UUID recorder = fixture.createAdministrator(tenant, "recorder+" + Uuid7.generate() + "@example.com");

        var caseId = new UUID[1];
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var signer = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = agreementSupport.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);
            var a = agreementRepository.findByCaseId(caseId[0]).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            signer[0] = fixture.createUser(tenant, "signer+" + Uuid7.generate() + "@example.com");
        });

        var latest = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> latest[0] = agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(List.of(
                        new SignatoryRequest(SignatoryKind.INTERNAL, null, signer[0], "Signer")), lockVersion[0])));
        fixture.runAsUser(tenant, editor, () -> latest[0] = agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2026, 10, 1), null, null, null, null,
                        latest[0].agreement().lockVersion())));
        fixture.runAsUser(tenant, submitter, () -> latest[0] = agreementService.submit(
                agreementId[0], latest[0].agreement().lockVersion()));
        fixture.runAsUser(tenant, reviewer, () -> latest[0] = agreementReviewService.review(
                agreementId[0], latest[0].versions().get(0).versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, latest[0].agreement().lockVersion())));
        fixture.runAsUser(tenant, submitter, () -> latest[0] = agreementService.send(
                agreementId[0], latest[0].agreement().lockVersion()));
        UUID signatoryId = latest[0].signatories().stream().map(AgreementSignatoryView::id).findFirst().orElseThrow();

        fixture.runAsUser(tenant, recorder, () -> agreementSignatureService.record(agreementId[0],
                new RecordSignatureRequest(signatoryId, LocalDate.now(clock), "Wet ink, scanned",
                        latest[0].agreement().lockVersion()), null, 0));

        fixture.runAs(tenant, () -> assertThat(chronological(caseId[0]))
                .containsSubsequence("case.created", "agreement.created")
                .containsSubsequence("agreement.signed", "requirement.satisfied", "milestone.completed"));
    }

    /**
     * A single stage/milestone whose one requirement is kind DOCUMENT,
     * published and opened -- the identical shape openCaseWithATaskRequirement
     * below already establishes for TASK, and DocumentInstantiationTest's own
     * openCaseWhoseFirstRequirementIsKindDocument for DOCUMENT specifically;
     * duplicated here in miniature (no requiresReview override needed, since
     * this test wants the default-false path) rather than shared, the same
     * choice openHeldCaseOnApprovedCustomerTemplate's own javadoc explains for
     * this class.
     */
    private UUID openCaseWithADocumentRequirement(UUID tenant) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(document("Provide NDA", "NDA")))))),
                List.of(), 0L);
        UUID templateId = workflows.createTemplate("Fixture Document " + Uuid7.generate(), "").id();
        UUID versionId = workflows.createDraft(templateId);
        workflows.replaceDraft(versionId, request);
        publishService.publish(versionId);

        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    /**
     * A customer-tier clone (single stage, single portal-visible milestone),
     * published, shape-submitted and APPROVED, cased, and then forced into
     * ON_HOLD directly against the repository -- Task 26 (not yet built) is
     * what will eventually make case creation on a customer template start this
     * way in production; seeded directly here rather than waiting on that
     * wiring, per this task's own pre-flight ruling. Same shape as {@code
     * PlanRevisionTest.openApprovedCustomerCase}/{@code
     * openHeldCaseOnCustomerTemplate}, duplicated here rather than shared: this
     * class runs everything as the tenant's fixture superuser and has no need
     * for PlanRevisionTest's narrow-scoped pm/am actors.
     */
    private UUID openHeldCaseOnApprovedCustomerTemplate(UUID tenant) {
        UUID catalogueTemplateId = workflows.createTemplate("Fixture Onboarding " + Uuid7.generate(), "").id();
        UUID catalogueDraftId = workflows.createDraft(catalogueTemplateId);
        workflows.replaceDraft(catalogueDraftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up")))))),
                List.of(), 0L));
        publishService.publish(catalogueDraftId);

        UUID customerId = fixture.createCustomer(tenant, "Plan Revision Customer " + Uuid7.generate(),
                null, null, null);
        var clone = customerTemplates.clone(catalogueTemplateId,
                new CloneTemplateRequest(customerId, "Plan Revision Clone " + Uuid7.generate()));
        UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
        publishService.publish(cloneVersionId);

        UUID contactId = fixture.createContact(tenant, customerId,
                "sponsor+" + Uuid7.generate() + "@cause-before-effect.example");
        planShapeService.submit(cloneVersionId);
        planShapeService.decide(cloneVersionId,
                new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));

        UUID caseId = cases.create(new CreateCaseRequest(customerId, clone.id(),
                "Plan Revision Case " + Uuid7.generate(), Map.of())).id();

        Case c = caseRepository.findById(caseId).orElseThrow();
        c.setStatus(CaseStatus.ON_HOLD);
        c.setHeldAt(Instant.now());
        caseRepository.saveAndFlush(c);

        return caseId;
    }

    /**
     * A single stage/milestone whose one requirement is kind TASK, published
     * and opened -- same shape as TaskInstantiationTest's own
     * openCaseWhoseFirstRequirementIsKindTask, needed here too since
     * publishedTemplate()'s own requirement is MANUAL and would never produce
     * a task.created event to assert on.
     */
    private UUID openCaseWithATaskRequirement(UUID tenant) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(task("Collect KYC pack")))))),
                List.of(), 0L);
        UUID versionId = journey.publish(request);
        UUID templateId = journey.templateOf(versionId);
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    /**
     * The timeline read is oldest-first (see AuditEventRepository), so this is
     * the order things actually happened, unreversed. Kept as a named helper
     * rather than inlined: if the read direction is ever flipped back, this is
     * the single place these order assertions need to adapt.
     */
    private List<String> chronological(UUID caseId) {
        return timeline.forCase(caseId, Pageable.ofSize(100)).getContent().stream()
                .map(AuditEventView::action)
                .toList();
    }

    private UUID openCase(UUID tenant) {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }
}
