package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.platform.storage.StorageProperties;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 8 (design spec 7): {@link AgreementFiles}, the one narrow facade that gives
 * {@code agreement} every document write it needs, gated only on {@code agreement.*}
 * permissions -- proving an agreement author needs no {@code document.*} permission
 * at all, and that a DRAFT agreement's file (always SENSITIVE, spec 7 above) cannot
 * leak through the existing portal document endpoints before it is sent.
 *
 * <p>Mirrors {@code document.DocumentWriteScopeTest}/{@code DocumentServiceTest}'s
 * own fixture shapes: {@code journey.newCase(tenant)} for a case with no active
 * stage (so {@code applyWriteScope} short-circuits and no {@code workflow.view}
 * grant is needed), and {@code journey.newCase(tenant, null, departmentId, null)}
 * where a real department-scoped write needs to be proven out of scope.
 */
class AgreementFilesTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementFiles agreementFiles;
    @Autowired DocumentContentService content;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired RoleService roles;
    @Autowired StorageProperties storageProperties;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /** Real bytes that sniff as text/html, dressed up as a PDF -- the same brief example every other module uses. */
    private static final byte[] HTML_BYTES =
            ("<!DOCTYPE html>\n<html><head><title>Not a PDF</title></head>"
                    + "<body>Not actually a PDF</body></html>").getBytes(StandardCharsets.UTF_8);

    @Test
    void createOwnedDocumentMakesASensitiveUntargetedAgreementCategoryDocumentOnTheCase() {
        UUID tenant = fixture.createTenant("agr-files-create-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "agr-create+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });

        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Master Services Agreement",
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        assertThat(owned.get().versionNumber()).isEqualTo(1);
        assertThat(owned.get().documentId()).isNotNull();
        assertThat(owned.get().documentVersionId()).isNotNull();

        fixture.runAs(tenant, () -> {
            Document d = documentRepository.findById(owned.get().documentId()).orElseThrow();
            assertThat(d.getCategory()).isEqualTo(DocumentCategory.AGREEMENT);
            assertThat(d.getVisibilityTier()).isEqualTo(VisibilityTier.SENSITIVE);
            assertThat(d.getTargetDepartmentId()).isNull();
            assertThat(d.getTargetContactLabel()).isNull();
            assertThat(d.getCaseId()).isEqualTo(caseId[0]);
        });
    }

    @Test
    void anAgreementManagerWithoutAnyDocumentPermissionCanCreateAndVersion() {
        UUID tenant = fixture.createTenant("agr-files-no-doc-perm-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "agr-noperm+" + Uuid7.generate() + "@example.com");
            // Only agreement.manage -- no document.upload, document.manage, document.view, nothing.
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });

        AtomicReference<OwnedFile> created = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> created.set(agreementFiles.createOwnedDocument(
                caseId[0], "Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        AtomicReference<OwnedFile> versioned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> versioned.set(agreementFiles.addDraftVersion(
                created.get().documentId(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        assertThat(versioned.get().versionNumber()).isEqualTo(2);
        assertThat(versioned.get().documentId()).isEqualTo(created.get().documentId());
    }

    @Test
    void theUploadIsHardenedExactlyLikeADocumentUpload() throws Exception {
        UUID tenant = fixture.createTenant("agr-files-hardened-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "agr-hardened+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });

        // Oversize -- refused before any row is written.
        long tooLarge = storageProperties.getMaxUploadBytes() + 1;
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementFiles.createOwnedDocument(
                caseId[0], "Too Big", new ByteArrayInputStream(PDF_BYTES), tooLarge)))
                .isInstanceOf(UploadTooLargeException.class);
        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());

        // Sniff mismatch -- real HTML bytes, declared byte-for-byte as if a PDF, refused
        // on what Tika actually sees, never on a filename or declared type.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementFiles.createOwnedDocument(
                caseId[0], "agreement.pdf", new ByteArrayInputStream(HTML_BYTES), HTML_BYTES.length)))
                .isInstanceOf(UnacceptableContentTypeException.class);
        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());

        // A real, accepted upload stores the real SHA-256 of the bytes actually written.
        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Real Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        String expectedSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(PDF_BYTES));
        assertThat(owned.get().sha256()).isEqualTo(expectedSha256);
        fixture.runAs(tenant, () -> {
            DocumentVersion v = versionRepository.findByDocumentId(owned.get().documentId()).get(0);
            assertThat(v.getSha256()).isEqualTo(expectedSha256);
        });
    }

    @Test
    void aPortalContactCannotDownloadTheSensitiveFileThroughTheDocumentContentEndpoint() {
        UUID tenant = fixture.createTenant("agr-files-portal-sensitive-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Agr Files Portal Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
            actor[0] = fixture.createUser(tenant, "agr-portal-mgr+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });
        UUID contactUserId = fixture.createPortalUserForContact(
                tenant, customerId[0], "reader@agr-files-portal-sensitive.example");

        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Draft Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, contactUserId, () ->
                content.open(owned.get().documentId(), 1)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void afterRetierToCompanySharedThePortalContactCanDownloadIt() {
        UUID tenant = fixture.createTenant("agr-files-portal-retier-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            customerId[0] = fixture.createCustomer(tenant, "Agr Files Retier Co " + Uuid7.generate(), null, null, null);
            caseId[0] = journey.newCaseForCustomer(tenant, customerId[0]).getId();
            actor[0] = fixture.createUser(tenant, "agr-retier-mgr+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });
        UUID contactUserId = fixture.createPortalUserForContact(
                tenant, customerId[0], "reader@agr-files-portal-retier.example");

        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Sent Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        // Sanity: refused before retier.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, contactUserId, () ->
                content.open(owned.get().documentId(), 1)))
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAsUser(tenant, actor[0], () ->
                agreementFiles.retier(owned.get().documentId(), VisibilityTier.COMPANY_SHARED));

        fixture.runAsUser(tenant, contactUserId, () -> {
            BlobContent opened = content.open(owned.get().documentId(), 1);
            assertThat(opened.filename()).isEqualTo("Sent Agreement");
        });

        fixture.runAs(tenant, () -> assertThat(
                documentRepository.findById(owned.get().documentId()).orElseThrow().getVisibilityTier())
                .isEqualTo(VisibilityTier.COMPANY_SHARED));
    }

    @Test
    void retierRefusesContactOnly() {
        UUID tenant = fixture.createTenant("agr-files-retier-refuses-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "agr-retier-refuse+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });

        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                agreementFiles.retier(owned.get().documentId(), VisibilityTier.CONTACT_ONLY)))
                .isInstanceOf(IllegalArgumentException.class);

        fixture.runAs(tenant, () -> assertThat(
                documentRepository.findById(owned.get().documentId()).orElseThrow().getVisibilityTier())
                .isEqualTo(VisibilityTier.SENSITIVE));
    }

    @Test
    void theFacadeRefusesADocumentThatIsNotAgreementCategory() {
        UUID tenant = fixture.createTenant("agr-files-wrong-category-" + Uuid7.generate());
        var actor = new UUID[1];
        var documentId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            UUID uploader = fixture.createUser(tenant, "agr-wrong-cat-uploader+" + Uuid7.generate() + "@example.com");
            actor[0] = fixture.createUser(tenant, "agr-wrong-cat-mgr+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
            documentId[0] = createOtherCategoryDocument(tenant, c, uploader);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                agreementFiles.addDraftVersion(documentId[0], new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)))
                .isInstanceOf(NoSuchElementException.class);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                agreementFiles.retier(documentId[0], VisibilityTier.COMPANY_SHARED)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anOutOfScopeCaseIsNotFound() {
        UUID tenant = fixture.createTenant("agr-files-out-of-scope-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID myDepartment = fixture.createDepartment(tenant, "My Dept " + Uuid7.generate());
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Dept " + Uuid7.generate());
            actor[0] = fixture.createUserInDepartment(
                    tenant, "agr-oos+" + Uuid7.generate() + "@example.com", myDepartment);
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.DEPARTMENT));

            // Owned by a DIFFERENT department -- out of the actor's own DEPARTMENT scope.
            caseId[0] = journey.newCase(tenant, null, otherDepartment, null).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> agreementFiles.createOwnedDocument(
                caseId[0], "Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)))
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    @Test
    void currentVersionReturnsTheLatestVersionAndItsSha256() throws Exception {
        UUID tenant = fixture.createTenant("agr-files-current-version-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "agr-current+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
        });

        AtomicReference<OwnedFile> created = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> created.set(agreementFiles.createOwnedDocument(
                caseId[0], "Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        String expectedSha256 = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(PDF_BYTES));

        AtomicReference<OwnedFile> current = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                current.set(agreementFiles.currentVersion(created.get().documentId())));

        assertThat(current.get().documentId()).isEqualTo(created.get().documentId());
        assertThat(current.get().documentVersionId()).isEqualTo(created.get().documentVersionId());
        assertThat(current.get().versionNumber()).isEqualTo(1);
        assertThat(current.get().sha256()).isEqualTo(expectedSha256);

        // A second version -- currentVersion must now report version 2, not the first.
        AtomicReference<OwnedFile> versioned = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> versioned.set(agreementFiles.addDraftVersion(
                created.get().documentId(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        AtomicReference<OwnedFile> currentAfter = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                currentAfter.set(agreementFiles.currentVersion(created.get().documentId())));

        assertThat(currentAfter.get().versionNumber()).isEqualTo(2);
        assertThat(currentAfter.get().documentVersionId()).isEqualTo(versioned.get().documentVersionId());
        assertThat(currentAfter.get().sha256()).isEqualTo(expectedSha256);
    }

    @Test
    void addCountersignedVersionNeedsSignRecordNotManage() {
        UUID tenant = fixture.createTenant("agr-files-sign-record-" + Uuid7.generate());
        var manager = new UUID[1];
        var signer = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            manager[0] = fixture.createUser(tenant, "agr-sign-mgr+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.AGREEMENT_MANAGE, Scope.ALL));
            signer[0] = fixture.createUser(tenant, "agr-sign-recorder+" + Uuid7.generate() + "@example.com");
            grant(signer[0], Map.of(PermissionKeys.AGREEMENT_SIGN_RECORD, Scope.ALL));
        });

        AtomicReference<OwnedFile> owned = new AtomicReference<>();
        fixture.runAsUser(tenant, manager[0], () -> owned.set(agreementFiles.createOwnedDocument(
                caseId[0], "Agreement", new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        // agreement.manage alone cannot record a countersigned copy.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () -> agreementFiles.addCountersignedVersion(
                owned.get().documentId(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)))
                .isInstanceOf(AccessDeniedException.class);

        // agreement.sign_record alone -- no agreement.manage, no document.* -- can.
        AtomicReference<OwnedFile> countersigned = new AtomicReference<>();
        fixture.runAsUser(tenant, signer[0], () -> countersigned.set(agreementFiles.addCountersignedVersion(
                owned.get().documentId(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length)));

        assertThat(countersigned.get().versionNumber()).isEqualTo(2);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    /** A plain, non-agreement document seeded directly -- AgreementFiles must never reach it. */
    private UUID createOtherCategoryDocument(UUID tenant, Case c, UUID uploadedBy) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
