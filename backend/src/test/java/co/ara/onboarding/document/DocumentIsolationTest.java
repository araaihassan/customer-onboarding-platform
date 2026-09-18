package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 21: the discoverability consolidation `task.TaskIsolationTest`/
 * `programme.ProgrammeIsolationTest` establish -- a short, representative file
 * naming where a reviewer finds document's cross-tenant proof, not a
 * duplicate of every per-method test already scattered across
 * {@code DocumentServiceTest}/{@code DocumentSharingServiceTest}.
 *
 * <p>The brief's own survey (repeated here, and confirmed by grepping this
 * module's two test files for {@code InAnotherTenantIsA404}) predicted every
 * one of {@code document}'s 8 write methods already carried its own dedicated
 * cross-tenant test. Re-checked directly rather than trusted: {@code patch},
 * {@code share} (both {@code documentId} and the principal id), {@code
 * revokeShare}, {@code link} (both {@code documentId} and the target {@code
 * caseId}) and {@code unlink} do. {@code upload}'s {@code caseId} argument,
 * {@code addVersion}'s {@code documentId} argument and {@code retire}'s
 * {@code id} argument do not -- a genuine gap the survey missed, not
 * something this file merely re-narrates. The three tests below close it;
 * they are new coverage, not a restatement of what Tasks 15/17/18 already
 * proved.
 *
 * <p>Every case here is CLAUDE.md's own write-path invariant: "a cross-tenant
 * id is consistently a 404, never the 200 a bypassed-RLS FK check would
 * produce, nor the 500 an invented id does" -- {@code AuthorizedQuery}
 * resolves the id under the row-level-security policy `RlsCoverageTest`
 * requires on every tenant-owned table, so a document (or the case an upload
 * targets) reachable only through another tenant's row is invisible at the
 * database layer before {@code DocumentDescriptor}'s own scope predicate is
 * ever evaluated.
 */
class DocumentIsolationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired DocumentRepository documentRepository;
    @Autowired RoleService roles;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /**
     * The case-id-from-a-request-body shape: {@code upload}'s first argument
     * is a case id, resolved through {@link co.ara.onboarding.authz.AuthorizedQuery}
     * under {@code document.upload} before anything is written -- a foreign
     * tenant's case is a 404 here, never the 500 an invented id would produce.
     */
    @Test
    void uploadOfACaseInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-iso-upload-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-iso-upload-b-" + Uuid7.generate());
        var foreignCaseId = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> foreignCaseId[0] = journey.newCase(tenantA).getId());

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "iso-upload-actor-b+" + Uuid7.generate() + "@example.com");
            grant(actorB[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0], () -> documents.upload(
                foreignCaseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The document-id-direct shape: {@code addVersion} resolves {@code documentId} under {@code document.upload} first. */
    @Test
    void addVersionOfADocumentInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-iso-addversion-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-iso-addversion-b-" + Uuid7.generate());
        var documentId = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "iso-addversion-uploader+" + Uuid7.generate() + "@example.com");
            documentId[0] = createDocument(tenantA, c, uploader);
        });

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "iso-addversion-actor-b+" + Uuid7.generate() + "@example.com");
            grant(actorB[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0], () -> documents.addVersion(
                documentId[0], new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The document-id-direct shape, again: {@code retire} resolves {@code id} under {@code document.manage} first. */
    @Test
    void retireOfADocumentInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-iso-retire-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-iso-retire-b-" + Uuid7.generate());
        var documentId = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "iso-retire-uploader+" + Uuid7.generate() + "@example.com");
            documentId[0] = createDocument(tenantA, c, uploader);
        });

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "iso-retire-actor-b+" + Uuid7.generate() + "@example.com");
            grant(actorB[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
        });

        // Never assert inside the runAs lambda -- catching there leaves the
        // transaction rollback-only and surfaces UnexpectedRollbackException
        // instead of the exception under test.
        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0], () ->
                documents.retire(documentId[0], "No longer needed")))
                .isInstanceOf(NoSuchElementException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createDocument(UUID tenant, Case c, UUID uploadedBy) {
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
