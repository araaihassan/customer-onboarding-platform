package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 13: {@link AgreementService#submit} -- freezing a draft into an immutable, hashed
 * {@link AgreementVersion} and moving the agreement to UNDER_REVIEW (spec sections 4.4.1/5.3).
 * {@code AgreementTestSupport} (Task 3/4) supplies every fixture row; {@code AgreementDraftTest}
 * (Task 12) already proved the draft-editing writes this class builds its fixtures on top of.
 *
 * <p>Sub-project 5's review/reject flow ({@code AgreementReviewService}) has no Task before this
 * one, so {@code aSecondSubmissionAfterRejectionIsVersionTwo} simulates "after rejection" the same
 * way {@code AgreementTestSupport#agreementRowInStatus} simulates any other status: by writing the
 * row directly back to DRAFT through the repository, never by inventing a reject call this task
 * does not own.
 */
class AgreementSubmitTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementVersionRepository versions;
    @Autowired CustomerContactRepository contacts;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void submitFreezesVersionOneWithSnapshotAndHashAndMovesToUnderReview() {
        UUID tenant = fixture.createTenant("agr-submit-freeze");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var result = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion()));

        assertThat(result.agreement().status()).isEqualTo(AgreementStatus.UNDER_REVIEW);
        assertThat(result.agreement().lockVersion()).isGreaterThan(afterUpload.agreement().lockVersion());
        assertThat(result.versions()).hasSize(1);
        AgreementVersionView v = result.versions().get(0);
        assertThat(v.versionNumber()).isEqualTo(1);
        assertThat(v.documentVersionId()).isNotNull();
        assertThat(v.documentSha256()).isNotBlank();
        assertThat(v.contentSha256()).hasSize(64);

        fixture.runAs(tenant, () -> assertThat(versions.maxVersionNumber(agreementId[0])).isEqualTo(1));
    }

    @Test
    void theVersionRecordsSubmitterAndLastEditorSeparately() {
        UUID tenant = fixture.createTenant("agr-submit-submitter-vs-editor");
        var editorA = new UUID[1];
        var submitterB = new UUID[1];
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            editorA[0] = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
            submitterB[0] = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        // Every draft edit -- patch, replaceSignatories, uploadDraftFile -- runs as editorA,
        // so lastEditedBy is A right up to the moment of submission.
        var afterPatch = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editorA[0], () -> afterPatch[0] = agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, LocalDate.of(2027, 1, 1), null, null, null, Set.of(), lockVersion[0])));

        var afterSig = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editorA[0], () -> afterSig[0] = agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, userId[0], "Approver")),
                        afterPatch[0].agreement().lockVersion())));

        var afterUpload = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editorA[0], () -> afterUpload[0] = agreementService.uploadDraftFile(agreementId[0],
                afterSig[0].agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        assertThat(afterUpload[0].agreement().lastEditedBy()).isEqualTo(editorA[0]);

        // Submitted by B -- a person who never touched a single draft field.
        var result = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitterB[0], () -> result[0] = agreementService.submit(
                agreementId[0], afterUpload[0].agreement().lockVersion()));

        assertThat(result[0].versions()).hasSize(1);
        AgreementVersionView v = result[0].versions().get(0);
        assertThat(v.submittedBy()).isEqualTo(submitterB[0]);
        assertThat(v.lastEditedBy()).isEqualTo(editorA[0]);
        assertThat(v.submittedBy()).isNotEqualTo(v.lastEditedBy());
    }

    @Test
    void aSecondSubmissionAfterRejectionIsVersionTwo() {
        UUID tenant = fixture.createTenant("agr-submit-second-version");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var firstSubmit = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion()));
        assertThat(firstSubmit.versions()).hasSize(1);

        // "After rejection" -- no AgreementReviewService exists yet (see this class's own
        // javadoc), so the row is put back to DRAFT directly, the same shape
        // AgreementTestSupport#agreementRowInStatus already uses for every other status.
        var backToDraftLockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = agreements.findById(agreementId[0]).orElseThrow();
            a.setStatus(AgreementStatus.DRAFT);
            agreements.saveAndFlush(a);
        });
        fixture.runAs(tenant, () -> backToDraftLockVersion[0] = agreements.findById(agreementId[0])
                .orElseThrow().getLockVersion());

        var secondSubmit = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], backToDraftLockVersion[0]));

        assertThat(secondSubmit.agreement().status()).isEqualTo(AgreementStatus.UNDER_REVIEW);
        assertThat(secondSubmit.versions()).hasSize(2);
        // Newest-first (AgreementVersionRepository#ofAgreementNewestFirst).
        assertThat(secondSubmit.versions().get(0).versionNumber()).isEqualTo(2);
        assertThat(secondSubmit.versions().get(1).versionNumber()).isEqualTo(1);
        fixture.runAs(tenant, () -> assertThat(versions.maxVersionNumber(agreementId[0])).isEqualTo(2));
    }

    @Test
    void zeroSignatoriesIsRefusedWithAMessageNamingTheProblem() {
        UUID tenant = fixture.createTenant("agr-submit-zero-signatories");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], lockVersion[0])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one signatory");

        fixture.runAs(tenant, () -> {
            assertThat(versions.maxVersionNumber(agreementId[0])).isEqualTo(0);
            assertThat(agreements.findById(agreementId[0]).orElseThrow().getStatus())
                    .isEqualTo(AgreementStatus.DRAFT);
        });
    }

    @Test
    void aSignatoryContactRetiredSinceBeingAddedIsRefusedAtSubmit() {
        UUID tenant = fixture.createTenant("agr-submit-retired-contact");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var contactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            contactId[0] = fixture.createContact(tenant, a.getCustomerId(), "sig-contact+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId[0],
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.CONTACT, contactId[0], null, "Customer Signatory")),
                        lockVersion[0])));
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        // Retired AFTER being added as a signatory -- replaceSignatories already accepted it
        // while it was still active; only submit re-checks.
        fixture.runAs(tenant, () -> {
            CustomerContact contact = contacts.findById(contactId[0]).orElseThrow();
            contact.setStatus(ContactStatus.INACTIVE);
            contacts.saveAndFlush(contact);
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no longer active");

        fixture.runAs(tenant, () -> assertThat(versions.maxVersionNumber(agreementId[0])).isEqualTo(0));
    }

    @Test
    void aFileIncludingModeWithoutAFileIsRefused() {
        UUID tenant = fixture.createTenant("agr-submit-needs-file");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            // draftAgreementRow's own default -- FILE_BACKED -- includesFile(), and no file
            // is ever uploaded in this test.
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterSig.agreement().lockVersion())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a file");
    }

    @Test
    void structuredModesNeedAnEffectiveDate() {
        UUID tenant = fixture.createTenant("agr-submit-needs-effective-date");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID caseId = support.openCaseWithSignatureRequirement(tenant, AgreementRecordMode.STRUCTURED_ONLY);
            Agreement a = agreements.findByCaseId(caseId).get(0);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterSig.agreement().lockVersion())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effective date");
    }

    @Test
    void fileBackedDoesNotNeedAnEffectiveDate() {
        UUID tenant = fixture.createTenant("agr-submit-file-backed-no-date");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        assertThat(afterUpload.agreement().effectiveDate()).isNull();

        var result = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion()));

        assertThat(result.agreement().status()).isEqualTo(AgreementStatus.UNDER_REVIEW);
        assertThat(result.agreement().effectiveDate()).isNull();
        assertThat(result.versions()).hasSize(1);
    }

    @Test
    void expiryNotAfterEffectiveDateIsRefused() {
        UUID tenant = fixture.createTenant("agr-submit-expiry-order");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        LocalDate same = LocalDate.of(2027, 3, 1);
        var afterPatch = fixture.runAsReturning(tenant, () -> agreementService.patch(agreementId[0],
                new PatchAgreementRequest(null, same, same, null, null, Set.of(),
                        afterUpload.agreement().lockVersion())));

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterPatch.agreement().lockVersion())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry date must be after");
    }

    @Test
    void theVersionsContentHashEqualsTheHasherOverTheSnapshotAndFileDigest() throws Exception {
        UUID tenant = fixture.createTenant("agr-submit-content-hash");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var result = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion()));

        String expectedFileSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(PDF_BYTES));

        AgreementSnapshot snapshot = new AgreementSnapshot(
                result.agreement().name(), result.agreement().recordMode(), result.agreement().effectiveDate(),
                result.agreement().expiresAt(), result.agreement().renewalDate(), result.agreement().noticePeriodDays(),
                result.signatories().stream()
                        .map(s -> new AgreementSnapshot.SignatorySnapshot(
                                s.id(), s.kind(), s.contactId(), s.userId(), s.displayRole(), s.sortOrder()))
                        .toList());
        String expectedContentSha256 = AgreementContentHasher.contentSha256(snapshot, expectedFileSha256);

        AgreementVersionView v = result.versions().get(0);
        assertThat(v.documentSha256()).isEqualTo(expectedFileSha256);
        assertThat(v.contentSha256()).isEqualTo(expectedContentSha256);
    }

    @Test
    void submitIsRefusedOutsideDraft() {
        UUID tenant = fixture.createTenant("agr-submit-status-guard");
        for (AgreementStatus status : List.of(AgreementStatus.UNDER_REVIEW, AgreementStatus.APPROVED,
                AgreementStatus.SENT, AgreementStatus.SIGNED, AgreementStatus.CANCELLED)) {
            var agreementId = new UUID[1];
            var lockVersion = new long[1];
            fixture.runAs(tenant, () -> {
                Case c = journey.newCase(tenant);
                Agreement a = support.agreementRowInStatus(tenant, c.getId(), status);
                agreementId[0] = a.getId();
                lockVersion[0] = a.getLockVersion();
            });

            assertThatThrownBy(() -> fixture.runAsReturning(tenant, () ->
                    agreementService.submit(agreementId[0], lockVersion[0])))
                    .as("status " + status)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void submitRecordsAgreementSubmitted() {
        UUID tenant = fixture.createTenant("agr-submit-audit");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        var caseId = new UUID[1];
        var userId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
            caseId[0] = a.getCaseId();
            userId[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });

        var afterSig = addInternalSignatory(tenant, agreementId[0], lockVersion[0], userId[0]);
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId[0],
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var result = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId[0], afterUpload.agreement().lockVersion()));

        fixture.runAs(tenant, () -> {
            List<AuditEvent> submitted = auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_SUBMITTED.key().equals(e.getAction()))
                    .toList();
            assertThat(submitted).hasSize(1);
            AuditEvent event = submitted.get(0);
            assertThat(event.getResourceType()).isEqualTo("onboarding_case");
            assertThat(event.getResourceId()).isEqualTo(caseId[0]);
            assertThat(event.isTimelineVisible()).isTrue();
            assertThat(event.getPayload())
                    .contains(agreementId[0].toString())
                    .contains(result.versions().get(0).contentSha256());
        });
    }

    private AgreementDetailView addInternalSignatory(UUID tenant, UUID agreementId, long lockVersion, UUID userId) {
        return fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId,
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, userId, "Approver")),
                        lockVersion)));
    }
}
