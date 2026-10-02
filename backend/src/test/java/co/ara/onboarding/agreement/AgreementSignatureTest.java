package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.document.DocumentVersionRepository;
import co.ara.onboarding.journey.CaseOnHoldException;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16: {@link AgreementSignatureService#record} -- recording one signatory's signature, and on the
 * last one satisfying the SIGNATURE requirement through the existing gated
 * {@code RequirementService.satisfy} (spec 5.5/5.6).
 */
class AgreementSignatureTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] COUNTERSIGNED_BYTES =
            "%PDF-1.4\n%âãÏÓ\n2 0 obj\n<< /Type /Catalog /Countersigned true >>\nendobj\ntrailer\n<< /Root 2 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementSignatureService signatureService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementSignatureRepository signatures;
    @Autowired AgreementVersionRepository versions;
    @Autowired RequirementRepository requirements;
    @Autowired RequirementService requirementService;
    @Autowired CaseService caseService;
    @Autowired DocumentVersionRepository documentVersions;
    @Autowired CustomerContactRepository contacts;
    @Autowired AuditEventRepository auditEvents;

    /** A SENT agreement: its id, lock version, signatory ids (in order), and the actor who can record. */
    private record Sent(UUID agreementId, long lockVersion, List<UUID> signatoryIds, UUID requirementId,
                        UUID caseId, UUID recorder) {}

    private static RecordSignatureRequest req(UUID signatoryId, LocalDate on, long lockVersion) {
        return new RecordSignatureRequest(signatoryId, on, "Wet ink, scanned", lockVersion);
    }

    private LocalDate today() { return LocalDate.now(clock); }

    @Test
    void theFirstOfTwoSignaturesMovesToAwaitingSignature() {
        UUID tenant = fixture.createTenant("agr-sig-first-of-two");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 2, false);

        AgreementDetailView v = asUser(tenant, s.recorder(), () -> signatureService.record(
                s.agreementId(), req(s.signatoryIds().get(0), today(), s.lockVersion()), null, 0));

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.AWAITING_SIGNATURE);
        assertThat(v.signatures()).hasSize(1);
        fixture.runAs(tenant, () -> assertThat(requirements.findById(s.requirementId()).orElseThrow().getStatus())
                .isEqualTo(RequirementStatus.OPEN));
    }

    @Test
    void theLastSignatureMovesToSignedSetsSignedAtAndSatisfiesTheRequirement() {
        UUID tenant = fixture.createTenant("agr-sig-last");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 2, false);
        AgreementDetailView first = sign(tenant, s, 0, s.lockVersion(), null);

        AgreementDetailView v = sign(tenant, s, 1, first.agreement().lockVersion(), null);

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        fixture.runAs(tenant, () -> {
            assertThat(agreements.findById(s.agreementId()).orElseThrow().getSignedAt()).isNotNull();
            assertThat(requirements.findById(s.requirementId()).orElseThrow().getStatus())
                    .isEqualTo(RequirementStatus.SATISFIED);
        });
    }

    @Test
    void theSatisfiedRefPointsAtTheAgreementWithRefTypeAgreement() {
        UUID tenant = fixture.createTenant("agr-sig-ref");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);
        sign(tenant, s, 0, s.lockVersion(), null);

        fixture.runAs(tenant, () -> {
            Requirement r = requirements.findById(s.requirementId()).orElseThrow();
            assertThat(r.getSatisfiedRef()).isEqualTo(s.agreementId());
            assertThat(r.getSatisfiedRefType()).isEqualTo("AGREEMENT");
            assertThat(AgreementSignatureService.SATISFIED_REF_TYPE).isEqualTo("AGREEMENT");
        });
    }

    @Test
    void aOneSignatoryAgreementGoesStraightFromSentToSigned() {
        UUID tenant = fixture.createTenant("agr-sig-one");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);

        AgreementDetailView v = sign(tenant, s, 0, s.lockVersion(), null);

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        fixture.runAs(tenant, () -> assertThat(auditEvents.findAll().stream()
                .filter(e -> AuditActions.AGREEMENT_SIGNED.key().equals(e.getAction())).toList()).hasSize(1));
    }

    @Test
    void eachSignatureCitesTheSentVersionAndCopiesItsContentHash() {
        UUID tenant = fixture.createTenant("agr-sig-cites-version");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 2, false);
        AgreementDetailView first = sign(tenant, s, 0, s.lockVersion(), null);
        sign(tenant, s, 1, first.agreement().lockVersion(), null);

        fixture.runAs(tenant, () -> {
            AgreementVersion sentVersion = versions.ofAgreementNewestFirst(s.agreementId()).get(0);
            List<AgreementSignature> rows = signatures.ofAgreement(s.agreementId());
            assertThat(rows).hasSize(2);
            assertThat(rows).allSatisfy(row -> {
                assertThat(row.getAgreementVersionId()).isEqualTo(sentVersion.getId());
                assertThat(row.getSignedContentSha256()).isEqualTo(sentVersion.getContentSha256());
                assertThat(row.getRecordedBy()).isEqualTo(s.recorder());
            });
        });
    }

    @Test
    void signingTheSameSignatoryTwiceIsRefused() {
        UUID tenant = fixture.createTenant("agr-sig-twice");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 2, false);
        AgreementDetailView first = sign(tenant, s, 0, s.lockVersion(), null);

        assertThatThrownBy(() -> sign(tenant, s, 0, first.agreement().lockVersion(), null))
                .isInstanceOf(IllegalStateException.class);
        fixture.runAs(tenant, () -> assertThat(signatures.ofAgreement(s.agreementId())).hasSize(1));
    }

    @Test
    void aSignatoryOfAnotherAgreementIsNotFound() {
        UUID tenant = fixture.createTenant("agr-sig-other-agreement");
        Sent a = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);
        Sent b = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);

        assertThatThrownBy(() -> asUser(tenant, a.recorder(), () -> signatureService.record(
                a.agreementId(), req(b.signatoryIds().get(0), today(), a.lockVersion()), null, 0)))
                .isInstanceOf(NoSuchElementException.class);
        fixture.runAs(tenant, () -> assertThat(signatures.ofAgreement(a.agreementId())).isEmpty());
    }

    @Test
    void theLastSignatureOfAFileIncludingAgreementRequiresTheCountersignedFile() {
        UUID tenant = fixture.createTenant("agr-sig-needs-file");
        Sent s = sent(tenant, AgreementRecordMode.FILE_BACKED, 1, true);

        assertThatThrownBy(() -> sign(tenant, s, 0, s.lockVersion(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    @Test
    void theCountersignedFileBecomesANewVersionOfTheAgreementsOwnDocument() {
        UUID tenant = fixture.createTenant("agr-sig-countersigned-version");
        Sent s = sent(tenant, AgreementRecordMode.FILE_BACKED, 1, true);
        var documentId = new UUID[1];
        var before = new int[1];
        fixture.runAs(tenant, () -> {
            documentId[0] = agreements.findById(s.agreementId()).orElseThrow().getDocumentId();
            before[0] = documentVersions.findByDocumentId(documentId[0]).size();
        });

        AgreementDetailView v = sign(tenant, s, 0, s.lockVersion(), COUNTERSIGNED_BYTES);

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        fixture.runAs(tenant, () -> {
            assertThat(documentVersions.findByDocumentId(documentId[0])).hasSize(before[0] + 1);
            AgreementSignature sig = signatures.ofAgreement(s.agreementId()).get(0);
            assertThat(sig.getCountersignedDocumentVersionId()).isNotNull();
            assertThat(documentVersions.findByDocumentId(documentId[0]))
                    .anyMatch(dv -> dv.getId().equals(sig.getCountersignedDocumentVersionId()));
        });
    }

    @Test
    void aCountersignedFileOnANonFinalSignatureIsRefused() {
        UUID tenant = fixture.createTenant("agr-sig-file-non-final");
        Sent s = sent(tenant, AgreementRecordMode.FILE_BACKED, 2, true);

        assertThatThrownBy(() -> sign(tenant, s, 0, s.lockVersion(), COUNTERSIGNED_BYTES))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    @Test
    void aStructuredOnlyAgreementRefusesAnyFile() {
        UUID tenant = fixture.createTenant("agr-sig-structured-file");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);

        assertThatThrownBy(() -> sign(tenant, s, 0, s.lockVersion(), COUNTERSIGNED_BYTES))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    @Test
    void aFutureSignedOnDateIsRefused() {
        UUID tenant = fixture.createTenant("agr-sig-future");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);

        assertThatThrownBy(() -> asUser(tenant, s.recorder(), () -> signatureService.record(
                s.agreementId(), req(s.signatoryIds().get(0), today().plusDays(1), s.lockVersion()), null, 0)))
                .isInstanceOf(IllegalArgumentException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    @Test
    void recordingIsRefusedBeforeSent() {
        UUID tenant = fixture.createTenant("agr-sig-before-sent");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> signatureService.record(
                agreementId[0], req(Uuid7.generate(), today(), lockVersion[0]), null, 0)))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Review Focus 2: satisfy() would overwrite a waiver, so the hook reads the status first. */
    @Test
    void aWaivedRequirementIsNotResatisfiedWhenTheAgreementIsSigned() {
        UUID tenant = fixture.createTenant("agr-sig-waived");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);
        fixture.runAsUser(tenant, s.recorder(), () -> requirementService.waive(s.requirementId(), "Not needed"));

        AgreementDetailView v = sign(tenant, s, 0, s.lockVersion(), null);

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        fixture.runAs(tenant, () -> {
            Requirement r = requirements.findById(s.requirementId()).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.WAIVED);
            assertThat(r.getWaiverReason()).isEqualTo("Not needed");
            assertThat(r.getSatisfiedRef()).isNull();
        });
    }

    /** Review Focus 1: the whole recording rolls back when satisfy() refuses a held case. */
    @Test
    void theFinalSignatureOnAHeldCaseRollsBackCompletely() {
        UUID tenant = fixture.createTenant("agr-sig-held");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);
        fixture.runAsUser(tenant, s.recorder(), () -> caseService.hold(s.caseId(), "Waiting on legal"));

        assertThatThrownBy(() -> sign(tenant, s, 0, s.lockVersion(), null))
                .isInstanceOf(CaseOnHoldException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    /** Review Focus 3: sign_record without milestone.complete is refused, atomically. */
    @Test
    void aSignRecordHolderWithoutMilestoneCompleteIsRefusedAtomically() {
        UUID tenant = fixture.createTenant("agr-sig-no-milestone-complete");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false);
        var narrow = new UUID[1];
        fixture.runAs(tenant, () -> narrow[0] = fixture.createUser(tenant, "narrow+" + Uuid7.generate() + "@example.com"));
        fixture.grantAtAllScope(tenant, narrow[0], PermissionKeys.AGREEMENT_SIGN_RECORD);
        fixture.grantAtAllScope(tenant, narrow[0], PermissionKeys.AGREEMENT_VIEW);
        fixture.grantAtAllScope(tenant, narrow[0], PermissionKeys.WORKFLOW_VIEW);

        assertThatThrownBy(() -> asUser(tenant, narrow[0], () -> signatureService.record(
                s.agreementId(), req(s.signatoryIds().get(0), today(), s.lockVersion()), null, 0)))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);

        assertNothingChanged(tenant, s, AgreementStatus.SENT);
    }

    /** Spec 5.5: the signature is a fact about a person who signed; retiring their contact later changes nothing. */
    @Test
    void aContactRetiredAfterSendingCanStillHaveTheirSignatureRecorded() {
        UUID tenant = fixture.createTenant("agr-sig-retired-contact");
        Sent s = sent(tenant, AgreementRecordMode.STRUCTURED_ONLY, 1, false, true);
        fixture.runAs(tenant, () -> {
            var agreement = agreements.findById(s.agreementId()).orElseThrow();
            var contactId = agreementService.get(agreement.getId()).signatories().get(0).contactId();
            var contact = contacts.findById(contactId).orElseThrow();
            contact.setStatus(ContactStatus.INACTIVE);
            contacts.saveAndFlush(contact);
        });

        AgreementDetailView v = sign(tenant, s, 0, s.lockVersion(), null);

        assertThat(v.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
    }

    // ---- helpers ----

    private <T> T asUser(UUID tenant, UUID user, java.util.function.Supplier<T> action) {
        var result = new java.util.concurrent.atomic.AtomicReference<T>();
        fixture.runAsUser(tenant, user, () -> result.set(action.get()));
        return result.get();
    }

    private AgreementDetailView sign(UUID tenant, Sent s, int signatoryIndex, long lockVersion, byte[] file) {
        return asUser(tenant, s.recorder(), () -> signatureService.record(
                s.agreementId(), req(s.signatoryIds().get(signatoryIndex), today(), lockVersion),
                file == null ? null : new ByteArrayInputStream(file), file == null ? 0 : file.length));
    }

    /** The three nothing-changed facts every refusal must leave behind. */
    private void assertNothingChanged(UUID tenant, Sent s, AgreementStatus expected) {
        fixture.runAs(tenant, () -> {
            assertThat(signatures.ofAgreement(s.agreementId())).isEmpty();
            assertThat(agreements.findById(s.agreementId()).orElseThrow().getStatus()).isEqualTo(expected);
            assertThat(requirements.findById(s.requirementId()).orElseThrow().getStatus())
                    .isEqualTo(RequirementStatus.OPEN);
            assertThat(auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_SIGNATURE_RECORDED.key().equals(e.getAction()))
                    .toList()).isEmpty();
        });
    }

    private Sent sent(UUID tenant, AgreementRecordMode mode, int signatories, boolean withFile) {
        return sent(tenant, mode, signatories, withFile, false);
    }

    /**
     * Drives a template-instantiated agreement (so the requirement really is a SIGNATURE one
     * inside a real milestone) through signatories, optional file, submit, review and send,
     * using three distinct administrators for the four-eyes rule. The recorder is a fourth.
     */
    private Sent sent(UUID tenant, AgreementRecordMode mode, int signatories, boolean withFile, boolean contactParties) {
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        UUID recorder = fixture.createAdministrator(tenant, "recorder+" + Uuid7.generate() + "@example.com");

        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var requirementId = new UUID[1];
        var caseId = new UUID[1];
        var parties = new ArrayList<SignatoryRequest>();
        fixture.runAs(tenant, () -> {
            UUID c = support.openCaseWithSignatureRequirement(tenant, mode);
            Agreement a = agreements.findByCaseId(c).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            requirementId[0] = a.getRequirementId();
            caseId[0] = c;
            for (int i = 0; i < signatories; i++) {
                if (contactParties) {
                    UUID contact = fixture.createContact(tenant, a.getCustomerId(), "signer" + i + "+" + Uuid7.generate() + "@example.com");
                    parties.add(new SignatoryRequest(SignatoryKind.CONTACT, contact, null, "Signer " + i));
                } else {
                    UUID user = fixture.createUser(tenant, "signer" + i + "+" + Uuid7.generate() + "@example.com");
                    parties.add(new SignatoryRequest(SignatoryKind.INTERNAL, null, user, "Signer " + i));
                }
            }
        });

        var latest = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> latest[0] = agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(parties, lockVersion[0])));
        if (withFile) {
            fixture.runAsUser(tenant, editor, () -> latest[0] = agreementService.uploadDraftFile(agreementId[0],
                    latest[0].agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
        } else {
            fixture.runAsUser(tenant, editor, () -> latest[0] = agreementService.patch(agreementId[0],
                    new PatchAgreementRequest(null, LocalDate.of(2026, 10, 1), null, null, null, null,
                            latest[0].agreement().lockVersion())));
        }
        fixture.runAsUser(tenant, submitter, () -> latest[0] = agreementService.submit(
                agreementId[0], latest[0].agreement().lockVersion()));
        fixture.runAsUser(tenant, reviewer, () -> latest[0] = reviewService.review(
                agreementId[0], latest[0].versions().get(0).versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, latest[0].agreement().lockVersion())));
        fixture.runAsUser(tenant, submitter, () -> latest[0] = agreementService.send(
                agreementId[0], latest[0].agreement().lockVersion()));

        List<UUID> ids = latest[0].signatories().stream().map(AgreementSignatoryView::id).toList();
        return new Sent(agreementId[0], latest[0].agreement().lockVersion(), ids, requirementId[0], caseId[0], recorder);
    }
}
