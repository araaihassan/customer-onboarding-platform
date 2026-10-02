package co.ara.onboarding.agreement;

import co.ara.onboarding.document.AgreementFiles;
import co.ara.onboarding.document.CreateDocumentRequest;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentContentService;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentService;
import co.ara.onboarding.document.DocumentSharingService;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.DocumentVersionRepository;
import co.ara.onboarding.document.DocumentView;
import co.ara.onboarding.document.PatchDocumentRequest;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Final whole-branch review, Importants 2 and 3: an agreement's own file is "mutable only via
 * its agreement" (spec 7; {@link AgreementFiles}'s javadoc), and after send a portal contact
 * sees only the sent version onward, never the internal drafting churn before it (decision 5).
 *
 * <p>Every general-purpose document write -- {@code DocumentService.addVersion}/{@code patch}/
 * {@code retire}, {@code DocumentSharingService.share}/{@code link} -- is refused as a 409 on an
 * agreement-owned document even for an Administrator holding every {@code document.*}
 * permission at ALL, while the agreement's own flow (upload, submit, review, send, record with
 * a countersigned file) keeps working, because it goes through {@link AgreementFiles}.
 */
class AgreementOwnedFileTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] OTHER_PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog /Altered true >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementSignatureService signatureService;
    @Autowired AgreementRepository agreements;
    @Autowired CaseRepository cases;
    @Autowired DocumentService documentService;
    @Autowired DocumentSharingService sharingService;
    @Autowired DocumentContentService content;
    @Autowired DocumentRepository documents;
    @Autowired DocumentVersionRepository documentVersions;
    @Autowired AgreementFiles agreementFiles;

    /** Actors and the agreement under test; every actor is a distinct Administrator. */
    private record World(UUID tenant, UUID editor, UUID submitter, UUID reviewer, UUID meddler,
                         UUID agreementId, UUID caseId, UUID customerId, UUID signatoryUser) {}

    private World world(String slug) {
        UUID tenant = fixture.createTenant(slug + "-" + Uuid7.generate());
        UUID editor = fixture.createAdministrator(tenant, "editor+" + Uuid7.generate() + "@example.com");
        UUID submitter = fixture.createAdministrator(tenant, "submitter+" + Uuid7.generate() + "@example.com");
        UUID reviewer = fixture.createAdministrator(tenant, "reviewer+" + Uuid7.generate() + "@example.com");
        UUID meddler = fixture.createAdministrator(tenant, "meddler+" + Uuid7.generate() + "@example.com");
        var agreement = new Agreement[1];
        var customer = new UUID[1];
        var signatory = new UUID[1];
        fixture.runAs(tenant, () -> {
            agreement[0] = support.draftAgreementRow(tenant);
            customer[0] = cases.findById(agreement[0].getCaseId()).orElseThrow().getCustomerId();
            signatory[0] = fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com");
        });
        return new World(tenant, editor, submitter, reviewer, meddler, agreement[0].getId(),
                agreement[0].getCaseId(), customer[0], signatory[0]);
    }

    private AgreementDetailView read(World w) {
        return fixture.runAsReturning(w.tenant(), () -> agreementService.get(w.agreementId()));
    }

    private AgreementDetailView upload(World w, byte[] bytes) {
        long lock = read(w).agreement().lockVersion();
        return runAs(w, w.editor(), () -> agreementService.uploadDraftFile(w.agreementId(), lock,
                new ByteArrayInputStream(bytes), bytes.length));
    }

    private AgreementDetailView runAs(World w, UUID actor, java.util.function.Supplier<AgreementDetailView> call) {
        AtomicReference<AgreementDetailView> out = new AtomicReference<>();
        fixture.runAsUser(w.tenant(), actor, () -> out.set(call.get()));
        return out.get();
    }

    private AgreementDetailView signatories(World w) {
        long lock = read(w).agreement().lockVersion();
        return runAs(w, w.editor(), () -> agreementService.replaceSignatories(w.agreementId(),
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, w.signatoryUser(), "Approver")),
                        lock)));
    }

    private AgreementDetailView submit(World w) {
        long lock = read(w).agreement().lockVersion();
        return runAs(w, w.submitter(), () -> agreementService.submit(w.agreementId(), lock));
    }

    private AgreementDetailView review(World w, ReviewDecision decision, String reason) {
        AgreementDetailView d = read(w);
        return runAs(w, w.reviewer(), () -> reviewService.review(w.agreementId(),
                d.versions().get(0).versionNumber(),
                new ReviewAgreementRequest(decision, reason, d.agreement().lockVersion())));
    }

    private AgreementDetailView send(World w) {
        long lock = read(w).agreement().lockVersion();
        return runAs(w, w.editor(), () -> agreementService.send(w.agreementId(), lock));
    }

    private AgreementDetailView recordWithCountersignedFile(World w) {
        AgreementDetailView d = read(w);
        RecordSignatureRequest r = new RecordSignatureRequest(d.signatories().get(0).id(),
                LocalDate.now(ZoneOffset.UTC).minusDays(1), "Wet ink", d.agreement().lockVersion());
        return runAs(w, w.editor(), () -> signatureService.record(w.agreementId(), r,
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
    }

    private UUID documentIdOf(World w) {
        return fixture.runAsReturning(w.tenant(), () -> agreements.findById(w.agreementId()).orElseThrow().getDocumentId());
    }

    private Document documentRow(World w, UUID documentId) {
        return fixture.runAsReturning(w.tenant(), () -> documents.findById(documentId).orElseThrow());
    }

    private int versionCount(World w, UUID documentId) {
        return fixture.runAsReturning(w.tenant(), () -> documentVersions.findByDocumentId(documentId).size());
    }

    /** Every general-purpose document write, attempted by an Administrator holding all document.* at ALL. */
    private void assertEveryGeneralDocumentWriteIsRefused(World w, UUID documentId) {
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.addVersion(
                documentId, new ByteArrayInputStream(OTHER_PDF_BYTES), OTHER_PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.patch(
                documentId, new PatchDocumentRequest("Renamed", null, null, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.patch(
                documentId, new PatchDocumentRequest(null, DocumentCategory.CONTRACT, null, null))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.retire(
                documentId, "No longer needed")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> sharingService.share(
                documentId, SharePrincipalType.USER, w.signatoryUser())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.meddler(), () -> sharingService.link(
                documentId, w.caseId())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyGeneralDocumentWriteIsRefusedWhileTheAgreementFlowStillWorks() {
        World w = world("agr-owned-file-flow");
        signatories(w);
        upload(w, PDF_BYTES);
        UUID documentId = documentIdOf(w);

        assertThat(documentRow(w, documentId).isAgreementOwned()).isTrue();

        // Before submit: no content injected around the agreement's own author (scenario b).
        assertEveryGeneralDocumentWriteIsRefused(w, documentId);

        // The agreement's own flow is untouched: a second draft upload, submit, approve, send.
        upload(w, PDF_BYTES);
        submit(w);
        review(w, ReviewDecision.APPROVE, null);
        send(w);

        // After approval and send: no swapping the file nobody reviewed in (scenario a), no
        // recategorising it out from under the facade, no retiring it under a SENT agreement (c).
        assertEveryGeneralDocumentWriteIsRefused(w, documentId);

        Document d = documentRow(w, documentId);
        assertThat(d.getCategory()).isEqualTo(DocumentCategory.AGREEMENT);
        assertThat(d.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(d.isAgreementOwned()).isTrue();
        assertThat(d.getName()).isNotEqualTo("Renamed");
        assertThat(versionCount(w, documentId)).isEqualTo(2);

        // Recording a signature with a countersigned file still appends through AgreementFiles.
        AgreementDetailView signed = recordWithCountersignedFile(w);
        assertThat(signed.agreement().status()).isEqualTo(AgreementStatus.SIGNED);
        assertThat(versionCount(w, documentId)).isEqualTo(3);

        // And cancel's retier still works on a live, sent agreement's file -- the agreement's own
        // path, not the general one. (A SIGNED agreement cannot be cancelled; check SENT instead.)
        World w2 = world("agr-owned-file-cancel");
        signatories(w2);
        upload(w2, PDF_BYTES);
        submit(w2);
        review(w2, ReviewDecision.APPROVE, null);
        send(w2);
        long lock = read(w2).agreement().lockVersion();
        runAs(w2, w2.editor(), () -> agreementService.cancel(w2.agreementId(),
                new CancelAgreementRequest("Terms changed", lock)));
        assertThat(documentRow(w2, documentIdOf(w2)).getVisibilityTier()).isEqualTo(VisibilityTier.SENSITIVE);
    }

    /**
     * The flag, not the category, is what marks an agreement's file: users could already pick
     * AGREEMENT as an ordinary category before this sub-project, and such a document stays fully
     * editable -- and recategorising an ordinary document never makes it agreement-owned.
     */
    @Test
    void anOrdinaryAgreementCategoryDocumentStaysEditableAndPatchNeverFlipsTheFlag() {
        World w = world("agr-owned-file-ordinary");

        DocumentView ordinary = uploadOrdinary(w, DocumentCategory.AGREEMENT);
        DocumentView other = uploadOrdinary(w, DocumentCategory.OTHER);

        fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.patch(
                ordinary.id(), new PatchDocumentRequest("Renamed ordinary", null, null, null)));
        fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.addVersion(
                ordinary.id(), new ByteArrayInputStream(OTHER_PDF_BYTES), OTHER_PDF_BYTES.length, "application/pdf"));
        fixture.runAsUser(w.tenant(), w.meddler(), () -> documentService.patch(
                other.id(), new PatchDocumentRequest(null, DocumentCategory.AGREEMENT, null, null)));

        assertThat(documentRow(w, ordinary.id()).getName()).isEqualTo("Renamed ordinary");
        assertThat(documentRow(w, ordinary.id()).isAgreementOwned()).isFalse();
        assertThat(versionCount(w, ordinary.id())).isEqualTo(2);
        assertThat(documentRow(w, other.id()).getCategory()).isEqualTo(DocumentCategory.AGREEMENT);
        assertThat(documentRow(w, other.id()).isAgreementOwned()).isFalse();

        // And the agreement facade still refuses both: an AGREEMENT category is not ownership.
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.editor(), () -> agreementFiles.addDraftVersion(
                other.id(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), w.editor(), () -> agreementFiles.retier(
                ordinary.id(), VisibilityTier.COMPANY_SHARED)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private DocumentView uploadOrdinary(World w, DocumentCategory category) {
        AtomicReference<DocumentView> out = new AtomicReference<>();
        fixture.runAsUser(w.tenant(), w.meddler(), () -> out.set(documentService.upload(w.caseId(),
                new CreateDocumentRequest("Ordinary " + category, category, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")));
        return out.get();
    }

    /**
     * Important 3: after send the document is COMPANY_SHARED, but a portal contact may open only
     * the version the agreement sent and anything appended after it (the countersigned copy) --
     * never v1, the draft Legal rejected. Staff still open every version.
     */
    @Test
    void aPortalContactOpensOnlyTheSentVersionOnward() {
        World w = world("agr-owned-file-portal");
        UUID portal = fixture.createPortalUserForContact(w.tenant(), w.customerId(),
                "portal+" + Uuid7.generate() + "@example.com");

        signatories(w);
        upload(w, OTHER_PDF_BYTES);                           // v1 -- will be rejected
        submit(w);
        review(w, ReviewDecision.REJECT, "clause 7 exposes us to unlimited liability; do not concede");
        upload(w, PDF_BYTES);                                 // v2 -- approved and sent
        submit(w);
        review(w, ReviewDecision.APPROVE, null);
        send(w);
        UUID documentId = documentIdOf(w);

        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), portal, () -> content.open(documentId, 1)))
                .isInstanceOf(NoSuchElementException.class);
        fixture.runAsUser(w.tenant(), portal, () -> assertThat(content.open(documentId, 2)).isNotNull());

        recordWithCountersignedFile(w);                      // v3 -- the countersigned copy
        fixture.runAsUser(w.tenant(), portal, () -> assertThat(content.open(documentId, 3)).isNotNull());
        assertThatThrownBy(() -> fixture.runAsUser(w.tenant(), portal, () -> content.open(documentId, 1)))
                .isInstanceOf(NoSuchElementException.class);

        // Staff open every version, the rejected draft included.
        for (int n = 1; n <= 3; n++) {
            int versionNo = n;
            fixture.runAsUser(w.tenant(), w.editor(), () -> assertThat(content.open(documentId, versionNo)).isNotNull());
        }
    }
}
