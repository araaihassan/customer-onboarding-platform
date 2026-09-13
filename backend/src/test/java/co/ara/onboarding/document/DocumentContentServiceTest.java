package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.platform.storage.BlobStore;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16: {@link DocumentContentService#open}, the download/streaming path --
 * the counterpart to Task 15's upload, gated {@code document.view} so
 * {@code scoping.DocumentAudienceFilter}'s targeting/tier/label narrowing
 * applies to CONTENT reads exactly as it already does to metadata reads
 * (spec 6.4; {@code security.DocumentAudienceTest} proves the metadata half,
 * and its own {@code documentManageCannotReadTheContentOfATargetedDocument}
 * flagged this as the gap this task closes end-to-end).
 *
 * {@link DocumentVersion} has a {@code ResourceAuthorizationDescriptor} (Task
 * 10) but NO {@code AudienceFilter} of its own -- only {@link Document} does
 * -- so {@code open} resolves the {@link Document} through
 * {@code AuthorizedQuery} FIRST (the audience-filtered read) and only THEN
 * looks up the specific version off the already-authorized document, never
 * through a second {@code AuthorizedQuery} call keyed on
 * {@code DocumentVersion.class}, which would bypass the audience mechanism
 * entirely.
 */
class DocumentContentServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired DocumentContentService content;
    @Autowired RoleService roles;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /**
     * {@code document.manage} is deliberately NOT narrowed by the audience
     * filter (spec 6.4) -- but the content endpoint is gated
     * {@code document.view}, a DIFFERENT permission. A holder of
     * {@code document.manage} alone, with no {@code document.view} at all,
     * must be refused by the permission gate itself, before this service's
     * own logic ever runs -- metadata authority over a document is not
     * download authority over its bytes.
     */
    @Test
    void documentManageOnlyHolderIsRefused() {
        UUID tenant = fixture.createTenant("doc-content-manage-only-" + Uuid7.generate());
        var uploader = new UUID[1];
        var reader = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            uploader[0] = fixture.createUser(tenant, "uploader+" + Uuid7.generate() + "@example.com");
            grant(uploader[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
            reader[0] = fixture.createUser(tenant, "manage-only+" + Uuid7.generate() + "@example.com");
            grant(reader[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
        });

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, uploader[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reader[0],
                () -> content.open(documentId[0], 1)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The same shape as {@code security.DocumentAudienceTest}'s core test,
     * proven end-to-end through the real content path this time: an
     * ALL-scoped {@code document.view} holder outside the document's targeted
     * department is still refused, exactly as a metadata read already is --
     * proving the CONTENT endpoint is narrowed too, not just the list/get
     * reads.
     */
    @Test
    void aTargetedDocumentIsRefusedToAnAllScopedDocumentViewHolderOutsideTheTarget() {
        UUID tenant = fixture.createTenant("doc-content-targeted-" + Uuid7.generate());
        var uploader = new UUID[1];
        var reader = new UUID[1];
        var caseId = new UUID[1];
        var legalDept = new UUID[1];
        fixture.runAs(tenant, () -> {
            legalDept[0] = fixture.createDepartment(tenant, "Legal");
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            uploader[0] = fixture.createUser(tenant, "targeted-uploader+" + Uuid7.generate() + "@example.com");
            grant(uploader[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));

            UUID otherDept = fixture.createDepartment(tenant, "Other");
            reader[0] = fixture.createUserInDepartment(
                    tenant, "outside-reader+" + Uuid7.generate() + "@example.com", otherDept);
        });
        fixture.grantAtAllScope(tenant, reader[0], PermissionKeys.DOCUMENT_VIEW);

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, uploader[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Legal-only Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        legalDept[0], null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reader[0],
                () -> content.open(documentId[0], 1)))
                .as("targeting binds even an ALL-scoped document.view holder outside the target department")
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The stream {@code open} returns is the exact bytes {@link BlobStore}
     * received at upload time -- read to the end and compared byte for byte,
     * not merely asserted non-empty.
     */
    @Test
    void theStreamIsTheExactBytesUploaded() throws Exception {
        UUID tenant = fixture.createTenant("doc-content-bytes-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            actor[0] = fixture.createUser(tenant, "bytes-actor+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        });

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, actor[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Bytes Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        var opened = new BlobContent[1];
        fixture.runAsUser(tenant, actor[0], () -> opened[0] = content.open(documentId[0], 1));

        byte[] read = opened[0].content().readAllBytes();
        assertThat(read).isEqualTo(PDF_BYTES);
        assertThat(opened[0].sizeBytes()).isEqualTo(PDF_BYTES.length);
        assertThat(opened[0].contentType()).isEqualTo("application/pdf");
    }

    /**
     * From Task 7's ruling: every document response carries
     * {@code Content-Disposition: attachment}, with the document's real
     * filename, never inline. There is no controller yet (Task 22 builds
     * it) -- an HTTP header cannot be asserted with nothing that sets one, so
     * this proves the DATA-SHAPE guarantee Task 22's controller will need to
     * actually set that header: {@link BlobContent#filename} carries the
     * document's real name (the schema's only filename basis --
     * {@link Document#getName()}, there is no separate filename column) and
     * {@link BlobContent#contentType} carries the sniffed type, not the
     * caller's declared one. Setting the literal HTTP header is Task 22's
     * job, not faked here against a controller that does not exist.
     */
    @Test
    void blobContentCarriesTheRealFilenameAndSniffedContentTypeForAnAttachmentDisposition() {
        UUID tenant = fixture.createTenant("doc-content-filename-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            actor[0] = fixture.createUser(tenant, "filename-actor+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        });

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, actor[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Master Services Agreement.pdf", DocumentCategory.CONTRACT,
                        VisibilityTier.COMPANY_SHARED, null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "declared-but-untrusted/type").id());

        var opened = new BlobContent[1];
        fixture.runAsUser(tenant, actor[0], () -> opened[0] = content.open(documentId[0], 1));

        assertThat(opened[0].filename()).isEqualTo("Master Services Agreement.pdf");
        assertThat(opened[0].contentType())
                .as("the sniffed type, never the caller's declared one")
                .isEqualTo("application/pdf");
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }
}
