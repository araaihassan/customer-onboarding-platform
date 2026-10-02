package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 15: {@link AgreementService#send} -- moving an APPROVED agreement to SENT through
 * the {@link SignatureProvider} seam (spec sections 3.4/4.6/5.3). {@code AgreementReviewTest}
 * (Task 14) already proved {@code review} freezes an agreement at APPROVED; this class builds
 * on the same three-actor (editor/submitter/reviewer) shape to reach that state, since the
 * self-review rule means a single administrator cannot submit-then-approve their own version.
 */
class AgreementSendTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementRepository agreements;
    @Autowired DocumentRepository documents;
    @Autowired AuditEventRepository auditEvents;
    @Autowired List<SignatureProvider> signatureProviders;

    /** Result of {@link #approveFileBacked}/{@link #approveStructuredOnly}: the agreement's
     * id and the lock version it holds right after moving to APPROVED. */
    private record Approved(UUID agreementId, long lockVersion) {}

    @Test
    void sendMovesAnApprovedAgreementToSentAndRecordsIt() {
        UUID tenant = fixture.createTenant("agr-send-moves-to-sent");
        var caseIdRef = new UUID[1];
        Approved approved = approveFileBacked(tenant, caseIdRef);

        AgreementDetailView result = fixture.runAsReturning(tenant,
                () -> agreementService.send(approved.agreementId(), approved.lockVersion()));

        assertThat(result.agreement().status()).isEqualTo(AgreementStatus.SENT);
        assertThat(result.agreement().lockVersion()).isGreaterThan(approved.lockVersion());

        fixture.runAs(tenant, () -> {
            List<AuditEvent> sentEvents = auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_SENT.key().equals(e.getAction()))
                    .toList();
            assertThat(sentEvents).hasSize(1);
            AuditEvent event = sentEvents.get(0);
            assertThat(event.getResourceType()).isEqualTo("onboarding_case");
            assertThat(event.getResourceId()).isEqualTo(caseIdRef[0]);
            assertThat(event.isTimelineVisible()).isTrue();
            assertThat(event.getPayload()).contains(approved.agreementId().toString());
        });
    }

    @Test
    void sendRetiersTheOwnedDocumentToCompanyShared() {
        UUID tenant = fixture.createTenant("agr-send-retiers-document");
        var caseIdRef = new UUID[1];
        Approved approved = approveFileBacked(tenant, caseIdRef);

        var documentIdRef = new UUID[1];
        fixture.runAs(tenant, () -> documentIdRef[0] =
                agreements.findById(approved.agreementId()).orElseThrow().getDocumentId());
        fixture.runAs(tenant, () -> assertThat(documents.findById(documentIdRef[0]).orElseThrow().getVisibilityTier())
                .isEqualTo(VisibilityTier.SENSITIVE));

        fixture.runAsReturning(tenant, () -> agreementService.send(approved.agreementId(), approved.lockVersion()));

        fixture.runAs(tenant, () -> {
            Document d = documents.findById(documentIdRef[0]).orElseThrow();
            assertThat(d.getVisibilityTier()).isEqualTo(VisibilityTier.COMPANY_SHARED);
        });
    }

    @Test
    void aStructuredOnlyAgreementSendsWithNoDocumentToRetier() {
        UUID tenant = fixture.createTenant("agr-send-structured-only");
        Approved approved = approveStructuredOnly(tenant);

        fixture.runAs(tenant, () -> assertThat(agreements.findById(approved.agreementId()).orElseThrow().getDocumentId())
                .isNull());

        AgreementDetailView result = fixture.runAsReturning(tenant,
                () -> agreementService.send(approved.agreementId(), approved.lockVersion()));

        assertThat(result.agreement().status()).isEqualTo(AgreementStatus.SENT);
        assertThat(result.agreement().documentId()).isNull();
    }

    @Test
    void theManualProviderStoresNoEnvelopeId() {
        UUID tenant = fixture.createTenant("agr-send-no-envelope-id");
        var caseIdRef = new UUID[1];
        Approved approved = approveFileBacked(tenant, caseIdRef);

        fixture.runAsReturning(tenant, () -> agreementService.send(approved.agreementId(), approved.lockVersion()));

        fixture.runAs(tenant, () -> assertThat(agreements.findById(approved.agreementId()).orElseThrow()
                .getProviderEnvelopeId()).isNull());
    }

    @Test
    void sendIsRefusedOutsideApproved() {
        UUID tenant = fixture.createTenant("agr-send-status-guard");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant,
                () -> agreementService.send(agreementId[0], lockVersion[0])))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Review Focus 4: a second send racing the same stale lockVersion is a conflict, not a
     * second transition. */
    @Test
    void aDoubleSendIsAConflictNotASecondTransition() {
        UUID tenant = fixture.createTenant("agr-send-double-conflict");
        var caseIdRef = new UUID[1];
        Approved approved = approveFileBacked(tenant, caseIdRef);

        fixture.runAsReturning(tenant, () -> agreementService.send(approved.agreementId(), approved.lockVersion()));

        assertThatThrownBy(() -> fixture.runAsReturning(tenant,
                () -> agreementService.send(approved.agreementId(), approved.lockVersion())))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        fixture.runAs(tenant, () -> assertThat(auditEvents.findAll().stream()
                .filter(e -> AuditActions.AGREEMENT_SENT.key().equals(e.getAction()))
                .toList()).hasSize(1));
    }

    /** No half-added OpenSign stub (CLAUDE.md "What sub-project 5 inherits"). */
    @Test
    void exactlyOneSignatureProviderBeanExistsAndItIsManual() {
        assertThat(signatureProviders).hasSize(1);
        assertThat(signatureProviders.get(0)).isInstanceOf(ManualSignatureProvider.class);
        assertThat(signatureProviders.get(0).kind()).isEqualTo(SignatureProviderKind.MANUAL);
    }

    /**
     * Builds a fresh FILE_BACKED draft, then runs signatory/upload/submit/review as three
     * distinct administrators (self-review refuses editor-equals-reviewer and
     * submitter-equals-reviewer alike) to reach APPROVED. {@code caseIdRef[0]} is filled with
     * the agreement's own case id, for tests asserting the audit event's resource id.
     */
    private Approved approveFileBacked(UUID tenant, UUID[] caseIdRef) {
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");

        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var signatoryUser = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            caseIdRef[0] = a.getCaseId();
            signatoryUser[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterSig[0] = agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, signatoryUser[0], "Approver")),
                        lockVersion[0])));

        var afterUpload = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterUpload[0] = agreementService.uploadDraftFile(agreementId[0],
                afterSig[0].agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var afterSubmit = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitter, () -> afterSubmit[0] = agreementService.submit(
                agreementId[0], afterUpload[0].agreement().lockVersion()));

        var afterReview = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewer, () -> afterReview[0] = reviewService.review(
                agreementId[0], afterSubmit[0].versions().get(0).versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, afterSubmit[0].agreement().lockVersion())));

        return new Approved(agreementId[0], afterReview[0].agreement().lockVersion());
    }

    /**
     * Same three-actor shape as {@link #approveFileBacked}, but against the agreement a
     * STRUCTURED_ONLY SIGNATURE requirement instantiates on case creation ({@code
     * AgreementTestSupport#openCaseWithSignatureRequirement}) -- no file is ever uploaded,
     * and {@code patch} supplies the effective date {@code submit} requires for a mode that
     * {@code includesFile()} is false for.
     */
    private Approved approveStructuredOnly(UUID tenant) {
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");

        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var signatoryUser = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);
            Agreement a = agreements.findByCaseId(caseId).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            signatoryUser[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterSig[0] = agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, signatoryUser[0], "Approver")),
                        lockVersion[0])));

        var afterPatch = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterPatch[0] = agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2026, 10, 1), null, null, null, null,
                        afterSig[0].agreement().lockVersion())));

        var afterSubmit = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitter, () -> afterSubmit[0] = agreementService.submit(
                agreementId[0], afterPatch[0].agreement().lockVersion()));

        var afterReview = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewer, () -> afterReview[0] = reviewService.review(
                agreementId[0], afterSubmit[0].versions().get(0).versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, afterSubmit[0].agreement().lockVersion())));

        return new Approved(agreementId[0], afterReview[0].agreement().lockVersion());
    }
}
