package co.ara.onboarding.document;

import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseOnHoldException;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.platform.storage.StorageProperties;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 14: DocumentService's read paths -- list, forCase, get. Every read goes
 * through AuthorizedQuery, which resolves scope against scoping.DocumentDescriptor
 * and ANDs scoping.DocumentAudienceFilter's targeting/sharing predicate on top
 * (Tasks 9-13, built and reviewed already; this is the first time either runs
 * through a real service method).
 */
class DocumentServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired DocumentContentService content;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired DocumentShareRepository shareRepository;
    @Autowired DocumentCaseLinkRepository linkRepository;
    @Autowired RoleService roles;
    @Autowired StorageProperties storageProperties;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired RequirementRepository requirementRepository;
    @Autowired AuditEventRepository auditEvents;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /** The brief's own example: real bytes that sniff as text/html, dressed up as a PDF upload. */
    private static final byte[] HTML_BYTES =
            ("<!DOCTYPE html>\n<html><head><title>Not a PDF</title></head>"
                    + "<body>Not actually a PDF</body></html>").getBytes(StandardCharsets.UTF_8);

    /**
     * A minimal but REAL OOXML package: a valid ZIP whose first entry is
     * {@code [Content_Types].xml}, exactly the shape every real OOXML writer
     * (Word, Excel, Apache POI, the OpenXML SDK) produces. Confirmed empirically
     * against the actual {@code tika-core} jar (no {@code tika-parsers}, so no
     * {@code ZipContainerDetector}) that this sniffs as {@code application/x-tika-ooxml}
     * -- Tika's own concrete {@code .docx}/{@code .xlsx} magic entries don't
     * exist at all, only a glob and a sub-class-of relationship, so a byte-only
     * sniff (no filename) can never produce the concrete type with this
     * dependency. See {@link ContentSniffGuard}'s own javadoc for the full
     * reasoning.
     */
    private static byte[] realOoxmlPackage() throws Exception {
        String contentTypesXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\""
                + "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "</Types>";
        String relsXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                + " Target=\"word/document.xml\"/></Relationships>";
        String documentXml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body><w:p><w:r><w:t>Hello</w:t></w:r></w:p></w:body></w:document>";

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            putZipEntry(zos, "[Content_Types].xml", contentTypesXml);
            putZipEntry(zos, "_rels/.rels", relsXml);
            putZipEntry(zos, "word/document.xml", documentXml);
        }
        return bos.toByteArray();
    }

    private static void putZipEntry(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /**
     * A minimal byte array satisfying Tika's own {@code application/msword}
     * magic rule exactly, verified against the actual jar: the 8-byte
     * OLE2/CFB signature at offset 0, plus the real internal stream name
     * Word writes ("WordDocument", UTF-16LE) placed inside the 1152-4096
     * byte window that rule inspects -- the same compound-file directory
     * sector region a genuine {@code .doc} produced by Word actually stores
     * that name in. Not a structurally-complete compound file (no real
     * FAT/directory chain), but every byte Tika's magic rule reads is exactly
     * what a real file contains at that position, which is the only thing a
     * magic-based sniff can ever see anyway.
     */
    private static byte[] realOle2WordDocument() {
        byte[] buf = new byte[4096];
        byte[] sig = {(byte) 0xd0, (byte) 0xcf, (byte) 0x11, (byte) 0xe0,
                      (byte) 0xa1, (byte) 0xb1, (byte) 0x1a, (byte) 0xe1};
        System.arraycopy(sig, 0, buf, 0, sig.length);
        byte[] streamName = "WordDocument".getBytes(StandardCharsets.UTF_16LE);
        System.arraycopy(streamName, 0, buf, 1152, streamName.length);
        return buf;
    }

    /**
     * At least one read test at the narrowest catalogued scope -- the same
     * convention CLAUDE.md states for writes ("wherever a permission is
     * catalogued at several scopes, at least one write test must run at the
     * narrowest one") applied here to a read. DOCUMENT_VIEW's ASSIGNED scope
     * (scoping.DocumentDescriptor.assignedScope) resolves off the document's
     * own uploaded_by column -- a personal relationship, never team-mediated
     * (CLAUDE.md's RelationshipType invariant) -- so a colleague's upload,
     * even one sitting on the SAME case, must not appear in list() and must
     * 404 on get(), not merely be narrower than an ALL-scoped read would show.
     */
    @Test
    void listAndGetAreNarrowedToTheCallersOwnUploadsAtAssignedScope() {
        UUID tenant = fixture.createTenant("doc-assigned-" + Uuid7.generate());
        var reader = new UUID[1];
        var ownDocumentId = new UUID[1];
        var colleaguesDocumentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            reader[0] = fixture.createUser(tenant, "reader+" + Uuid7.generate() + "@example.com");
            grant(reader[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ASSIGNED));

            UUID colleague = fixture.createUser(tenant, "colleague+" + Uuid7.generate() + "@example.com");
            ownDocumentId[0] = createDocument(tenant, c, reader[0]);
            colleaguesDocumentId[0] = createDocument(tenant, c, colleague);
        });

        AtomicReference<List<DocumentView>> listed = new AtomicReference<>();
        fixture.runAsUser(tenant, reader[0], () ->
                listed.set(documents.list(null, Pageable.unpaged()).getContent()));
        assertThat(listed.get()).extracting(DocumentView::id).containsExactly(ownDocumentId[0]);

        fixture.runAsUser(tenant, reader[0], () ->
                assertThat(documents.get(ownDocumentId[0]).id()).isEqualTo(ownDocumentId[0]));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reader[0],
                () -> documents.get(colleaguesDocumentId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The home-or-linked filter (the brief's own words: "the home-or-linked
     * filter is an extra Specification -- not part of the audience filter,
     * which answers WHO, not WHERE"), proven at ALL scope deliberately --
     * Task 10's review already found DocumentDescriptor's DEPARTMENT/TEAM/
     * ASSIGNED predicates match only a document's HOME case, not one it is
     * merely linked into, so a narrower-scoped test here would conflate that
     * separate, already-tracked gap with this filter's own correctness. Also
     * proves a REVOKED link no longer counts -- "unlink is a column, not a
     * DELETE", so the document must vanish from this case's list again once
     * revoked, not just fail to be added twice.
     */
    @Test
    void forCaseReturnsHomeAndLinkedDocumentsButNotOthers() {
        UUID tenant = fixture.createTenant("doc-forcase-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseAId = new UUID[1];
        var homeDocId = new UUID[1];
        var linkedDocId = new UUID[1];
        var revokedLinkDocId = new UUID[1];
        var unrelatedDocId = new UUID[1];

        fixture.runAs(tenant, () -> {
            Case caseA = journey.newCase(tenant);
            Case caseB = journey.newCase(tenant);
            Case caseC = journey.newCase(tenant);
            Case caseD = journey.newCase(tenant);
            caseAId[0] = caseA.getId();

            actor[0] = fixture.createUser(tenant, "forcase-actor+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            homeDocId[0] = createDocument(tenant, caseA, actor[0]);

            UUID caseBDoc = createDocument(tenant, caseB, actor[0]);
            linkedDocId[0] = caseBDoc;
            linkRepository.saveAndFlush(new DocumentCaseLink(
                    Uuid7.generate(), tenant, caseBDoc, caseA.getId(), actor[0], Instant.now(clock)));

            UUID caseDDoc = createDocument(tenant, caseD, actor[0]);
            revokedLinkDocId[0] = caseDDoc;
            DocumentCaseLink revoked = new DocumentCaseLink(
                    Uuid7.generate(), tenant, caseDDoc, caseA.getId(), actor[0], Instant.now(clock));
            revoked.setRevokedAt(Instant.now(clock));
            linkRepository.saveAndFlush(revoked);

            unrelatedDocId[0] = createDocument(tenant, caseC, actor[0]);
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                result.set(documents.forCase(caseAId[0], Pageable.unpaged()).getContent()));

        assertThat(result.get()).extracting(DocumentView::id)
                .containsExactlyInAnyOrder(homeDocId[0], linkedDocId[0]);
    }

    /**
     * Review round 1, Important #1/#2: forCase now resolves caseId through
     * AuthorizedQuery FIRST (the same "confirm the parent before listing its
     * children" idiom TaskService.forCase/ApprovalService.listForCase/
     * PlanRevisionService.listForCase already use), so a cross-tenant caseId
     * must 404 here too, not just on get(). RLS on Case alone would already
     * make this true even without the new resolution, but this test pins the
     * behaviour down explicitly now that the resolution is a real code path
     * rather than absent.
     */
    @Test
    void forCaseWithACrossTenantCaseIdIsA404() {
        UUID tenantA = fixture.createTenant("doc-forcase-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-forcase-tenant-b-" + Uuid7.generate());
        var caseInA = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> caseInA[0] = journey.newCase(tenantA).getId());

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "forcase-actor-b+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenantB, actorB[0], PermissionKeys.DOCUMENT_VIEW);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0],
                () -> documents.forCase(caseInA[0], Pageable.unpaged())))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Review round 1, Important #2's other half: a SAME-tenant caseId outside
     * the caller's own DOCUMENT_VIEW scope -- DEPARTMENT here, the narrowest
     * scope with a real ownership column to test against (ASSIGNED is
     * already covered above for list()/get(), and doesn't apply to Case at
     * all: CaseDescriptor's ASSIGNED resolves through case_participant, which
     * this scenario does not need). Proves the new resolution genuinely
     * narrows by scope, not merely by tenant -- an actor granted document.view
     * at DEPARTMENT only must not be able to list documents "for" a case
     * owned by a DIFFERENT department, even within the same tenant.
     */
    @Test
    void forCaseWithACaseOutsideTheCallersDepartmentScopeIsA404() {
        UUID tenant = fixture.createTenant("doc-forcase-scope-" + Uuid7.generate());
        var actor = new UUID[1];
        var otherDeptCaseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID ownDepartment = fixture.createDepartment(tenant, "Reader's Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Department");
            actor[0] = fixture.createUserInDepartment(
                    tenant, "forcase-dept-reader+" + Uuid7.generate() + "@example.com", ownDepartment);
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.DEPARTMENT));

            otherDeptCaseId[0] = journey.newCase(tenant, null, otherDepartment, null).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0],
                () -> documents.forCase(otherDeptCaseId[0], Pageable.unpaged())))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Review round 1, Important #1's own fail-closed guard, proven live rather
     * than left as an untested branch: PortalPermissions grants document.view
     * at Scope.ALL with no AudienceFilter on Case to narrow it, so forCase
     * refuses a portal actor outright before ever resolving caseId, even
     * against a case belonging to the portal contact's OWN customer. Spec §8
     * never actually routes a portal caller here (GET /portal/documents takes
     * no caseId), so this is a safety net on the method's own contract, not a
     * path expected to fire in production -- but it must still demonstrably
     * refuse, not silently do nothing.
     */
    @Test
    void forCaseRefusesAPortalActorEvenForTheirOwnCustomersCase() {
        UUID tenant = fixture.createTenant("doc-forcase-portal-" + Uuid7.generate());
        var portalUserId = new UUID[1];
        var ownCustomersCaseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Portal Co " + Uuid7.generate(), null, null, null);
            portalUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "portal-contact+" + Uuid7.generate() + "@example.com");
            ownCustomersCaseId[0] = journey.newCase(tenant).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, portalUserId[0],
                () -> documents.forCase(ownCustomersCaseId[0], Pageable.unpaged())))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The write-path-shaped invariant applied to a read: a cross-tenant id is a 404, never a 500. */
    @Test
    void getOfADocumentInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-tenant-b-" + Uuid7.generate());
        var documentId = new UUID[1];
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "uploader+" + Uuid7.generate() + "@example.com");
            documentId[0] = createDocument(tenantA, c, uploader);
        });

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "actor-b+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenantB, actorB[0], PermissionKeys.DOCUMENT_VIEW);
        });

        // Never assert inside the runAs lambda -- catching there leaves the
        // transaction rollback-only and surfaces UnexpectedRollbackException
        // instead of the exception under test.
        assertThatThrownBy(() -> fixture.runAsUser(tenantB, actorB[0], () -> documents.get(documentId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Task 15: upload creates a document pinned to version 1, with
     * {@code customerId} copied from the RESOLVED case -- {@link CreateDocumentRequest}
     * carries no such field at all, so there is nothing a caller could even
     * send to override it (CLAUDE.md's write-path invariant). The version's
     * own {@code content_type} is the SNIFFED type, not the caller's declared
     * one (a mismatched declared type is passed deliberately, proving it is
     * ignored for storage).
     */
    @Test
    void uploadingADocumentPinsVersionOneAndCopiesCustomerIdFromTheResolvedCase() {
        UUID tenant = fixture.createTenant("doc-upload-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        var customerId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            customerId[0] = c.getCustomerId();
            actor[0] = fixture.createUser(tenant, "uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("Master Services Agreement", DocumentCategory.CONTRACT,
                        VisibilityTier.COMPANY_SHARED, null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length,
                "declared-but-untrusted/type")));

        assertThat(uploaded.get().customerId()).isEqualTo(customerId[0]);
        assertThat(uploaded.get().currentVersionId()).isNotNull();
        // Ruling 3 (sub-project 4 Task 33): currentVersionNumber is the version
        // NUMBER the content-download endpoint actually takes, resolved from
        // currentVersionId -- a fresh upload's first version is always 1.
        assertThat(uploaded.get().currentVersionNumber()).isEqualTo(1);

        fixture.runAs(tenant, () -> {
            List<DocumentVersion> versions = versionRepository.findByDocumentId(uploaded.get().id());
            assertThat(versions).hasSize(1);
            assertThat(versions.get(0).getVersionNo()).isEqualTo(1);
            assertThat(versions.get(0).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
            assertThat(versions.get(0).getContentType()).isEqualTo("application/pdf");
        });
    }

    /**
     * The confused-deputy gap this test exists to close: {@code
     * CreateDocumentRequest.ownerContactId} used to be written straight from
     * the request body with no cross-reference check at all, unlike every
     * other id this module's write paths accept (CLAUDE.md's write-path
     * invariant, and the identical shape {@code DocumentSharingService
     * #resolveContact}/{@code DocumentSharingService#link} and {@code
     * DocumentRequestService}'s own contact resolution already carry).
     * {@code owner_contact_id} is exactly what gates CONTACT_ONLY visibility
     * ({@code scoping.DocumentAudienceFilter}), but that filter's own
     * {@code atMyCustomer} conjunct already independently stops a cross-customer
     * owner from ever disclosing the document to the wrong customer's portal
     * user -- so this test is NOT proving a disclosure fix. What it proves
     * instead: an internal actor could otherwise upload a CONTACT_ONLY document
     * whose owner belongs to a different customer than the case, which the FK
     * alone cannot catch (it only proves the contact exists somewhere in the
     * tenant) and which would make the document permanently unreachable by
     * anyone -- and this refuses that with a clean {@link IllegalArgumentException}
     * (400) instead of either silently persisting an orphaned document or
     * surfacing a raw FK violation. Never the actor's own scope, and never the
     * 404 an absent or out-of-scope contact id already gets from
     * {@code AuthorizedQuery#getById} on its own.
     */
    @Test
    void uploadRefusesAnOwnerContactBelongingToADifferentCustomerThanTheCase() {
        UUID tenant = fixture.createTenant("doc-owner-mismatch-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        var otherContactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            actor[0] = fixture.createUser(tenant, "owner-mismatch+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));

            UUID otherCustomerId = fixture.createCustomer(
                    tenant, "Other Owner Customer " + Uuid7.generate(), null, null, null);
            otherContactId[0] = fixture.createContact(
                    tenant, otherCustomerId, "other-owner+" + Uuid7.generate() + "@example.com");
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> documents.upload(caseId[0],
                new CreateDocumentRequest("KYC Doc", DocumentCategory.KYC, VisibilityTier.CONTACT_ONLY,
                        null, null, otherContactId[0], null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(IllegalArgumentException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    /** The positive counterpart: a contact genuinely belonging to the SAME customer as the case is accepted and round-trips. */
    @Test
    void uploadAcceptsAnOwnerContactBelongingToTheSameCustomerAsTheCase() {
        UUID tenant = fixture.createTenant("doc-owner-match-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        var contactId = new UUID[1];
        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            actor[0] = fixture.createUser(tenant, "owner-match+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            contactId[0] = fixture.createContact(
                    tenant, c.getCustomerId(), "owner-match-contact+" + Uuid7.generate() + "@example.com");
        });

        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("KYC Doc", DocumentCategory.KYC, VisibilityTier.CONTACT_ONLY,
                        null, null, contactId[0], null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")));

        assertThat(uploaded.get().ownerContactId()).isEqualTo(contactId[0]);
    }

    /** No regression: a null ownerContactId (no CONTACT_ONLY targeting) still uploads without resolving anything. */
    @Test
    void uploadWithANullOwnerContactIdStillSucceeds() {
        UUID tenant = fixture.createTenant("doc-owner-null-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "owner-null+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")));

        assertThat(uploaded.get().ownerContactId()).isNull();
    }

    /**
     * The digest is the actual SHA-256 of the bytes written, computed from the
     * same stream {@code BlobStore.put} consumes -- not derived from the
     * request's declared metadata, which is why the request above already
     * carries a deliberately wrong declared type.
     */
    @Test
    void aSuccessfulUploadsSha256IsTheActualDigestOfTheUploadedBytes() throws Exception {
        UUID tenant = fixture.createTenant("doc-sha256-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "sha-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("Cert", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")));

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PDF_BYTES));
        fixture.runAs(tenant, () -> {
            DocumentVersion v = versionRepository.findByDocumentId(uploaded.get().id()).get(0);
            assertThat(v.getSha256()).isEqualTo(expected);
        });
    }

    /**
     * version_no starts at 1 (upload) and increments by one on each subsequent
     * addVersion call. Also pins {@code DocumentView.currentVersionNumber}
     * (Ruling 3, Task 33) against a document that has actually been through
     * more than one append -- the field's own mechanism (`toView` resolving
     * it from `currentVersionId`, which `addVersion` keeps in step on every
     * append) was otherwise only proven against a single-version upload
     * elsewhere in this file, which cannot tell a correctly-updated field
     * apart from one that happens to start right and never moves. This is
     * the regression the field exists to prevent: a stale "always shows v1"
     * Open link once a document has more than one version.
     */
    @Test
    void versionNumbersStartAtOneAndIncrementOnEachAppendedVersion() {
        UUID tenant = fixture.createTenant("doc-vno-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "vno-uploader+" + Uuid7.generate() + "@example.com");
            // DOCUMENT_VIEW alongside DOCUMENT_UPLOAD -- this test also reads
            // the document back through documents.get to assert
            // currentVersionNumber, which is gated separately from upload.
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL, PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        });

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, actor[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        fixture.runAsUser(tenant, actor[0], () -> documents.addVersion(documentId[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf"));
        fixture.runAsUser(tenant, actor[0], () -> documents.addVersion(documentId[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf"));

        fixture.runAs(tenant, () -> {
            List<Integer> versionNos = versionRepository.findByDocumentId(documentId[0]).stream()
                    .map(DocumentVersion::getVersionNo).sorted().toList();
            assertThat(versionNos).containsExactly(1, 2, 3);
        });

        fixture.runAsUser(tenant, actor[0],
                () -> assertThat(documents.get(documentId[0]).currentVersionNumber()).isEqualTo(3));
    }

    /** A new version always starts PENDING, even when an earlier version was already approved. */
    @Test
    void aNewVersionResetsReviewStatusToPendingEvenWhenAnEarlierVersionWasApproved() {
        UUID tenant = fixture.createTenant("doc-reset-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "reset-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        var documentId = new UUID[1];
        fixture.runAsUser(tenant, actor[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        fixture.runAs(tenant, () -> {
            DocumentVersion v1 = versionRepository.findByDocumentId(documentId[0]).get(0);
            v1.setReviewStatus(ReviewStatus.APPROVED);
            versionRepository.saveAndFlush(v1);
        });

        fixture.runAsUser(tenant, actor[0], () -> documents.addVersion(documentId[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf"));

        fixture.runAs(tenant, () -> {
            DocumentVersion v2 = versionRepository.findByDocumentId(documentId[0]).stream()
                    .filter(v -> v.getVersionNo() == 2).findFirst().orElseThrow();
            assertThat(v2.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        });
    }

    /**
     * Task 7's ruling: the declared size is checked against
     * app.storage.max-upload-bytes BEFORE the stream is touched at all -- the
     * actual bytes handed in are a normal, small PDF, and the refusal still
     * fires purely off the declared (and here deliberately inflated) size.
     * Writes no row and no blob.
     */
    @Test
    void anUploadExceedingTheConfiguredCeilingIsRefusedWritingNoRow() {
        UUID tenant = fixture.createTenant("doc-toolarge-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "toolarge-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        long tooLarge = storageProperties.getMaxUploadBytes() + 1;
        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> documents.upload(caseId[0],
                new CreateDocumentRequest("Big", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), tooLarge, "application/pdf")))
                .isInstanceOf(UploadTooLargeException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    /**
     * Task 7's ruling, the sniffed-content half: real HTML bytes, declared as
     * a CONTRACT (which only accepts PDF/DOC/DOCX), is refused because the
     * bytes themselves sniff as text/html -- never because of a filename or
     * the caller's declared Content-Type, which here is a bald-faced lie
     * ("application/pdf") that the check does not even consult. Writes no row.
     */
    @Test
    void anUploadWhoseSniffedContentDoesNotMatchItsDeclaredCategoryIsRefused() {
        UUID tenant = fixture.createTenant("doc-sniff-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "sniff-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () -> documents.upload(caseId[0],
                new CreateDocumentRequest("contract.pdf", DocumentCategory.CONTRACT, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(HTML_BYTES), HTML_BYTES.length, "application/pdf")))
                .isInstanceOf(UnacceptableContentTypeException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    /**
     * Review round 1, Important #2: tika-core alone has no ZipContainerDetector,
     * so it can never produce the concrete DOCX mime type -- only the generic
     * application/x-tika-ooxml a real Office package sniffs as (see
     * ContentSniffGuard's own javadoc). This proves the allowlist fix actually
     * lets a genuine Word document through, using REAL OOXML bytes rather than
     * a guess at what Tika would say.
     */
    @Test
    void uploadSucceedsForARealMinimalOoxmlDocxPackage() throws Exception {
        UUID tenant = fixture.createTenant("doc-ooxml-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "ooxml-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        byte[] docx = realOoxmlPackage();
        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("contract.docx", DocumentCategory.CONTRACT, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(docx), docx.length, "application/octet-stream")));

        fixture.runAs(tenant, () -> {
            DocumentVersion v = versionRepository.findByDocumentId(uploaded.get().id()).get(0);
            assertThat(v.getContentType()).isEqualTo("application/x-tika-ooxml");
        });
    }

    /**
     * Review round 1, Important #2's other half: a real, unmodified legacy
     * .doc (OLE2/CFB) sniffs correctly via magic bytes alone, with no
     * allowlist change needed -- confirmed against the actual tika-core jar
     * before and after the OOXML fix, so this is a regression guard, not a
     * new capability.
     */
    @Test
    void uploadSucceedsForARealOle2StyleWordDocument() {
        UUID tenant = fixture.createTenant("doc-ole2-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "ole2-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        byte[] doc = realOle2WordDocument();
        AtomicReference<DocumentView> uploaded = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> uploaded.set(documents.upload(caseId[0],
                new CreateDocumentRequest("contract.doc", DocumentCategory.CONTRACT, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(doc), doc.length, "application/octet-stream")));

        fixture.runAs(tenant, () -> {
            DocumentVersion v = versionRepository.findByDocumentId(uploaded.get().id()).get(0);
            assertThat(v.getContentType()).isEqualTo("application/msword");
        });
    }

    /**
     * Review round 1, Important #1: the same portal-actor case-resolution gap
     * Task 14 fixed for forCase, proven closed for upload too. A portal
     * contact holds document.upload at Scope.ALL with no AudienceFilter on
     * Case to narrow it -- refused outright before caseId is even resolved,
     * even against a case belonging to the portal contact's OWN customer
     * (spec §8 never routes a portal caller through this exact signature
     * anyway; this is a fail-closed guard on the method's own contract).
     */
    @Test
    void uploadRefusesAPortalActorEvenForTheirOwnCustomersCase() {
        UUID tenant = fixture.createTenant("doc-upload-portal-" + Uuid7.generate());
        var portalUserId = new UUID[1];
        var ownCustomersCaseId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Portal Upload Co " + Uuid7.generate(), null, null, null);
            portalUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerId, "portal-upload-contact+" + Uuid7.generate() + "@example.com");
            ownCustomersCaseId[0] = journey.newCase(tenant).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, portalUserId[0], () -> documents.upload(
                ownCustomersCaseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(ownCustomersCaseId[0])).isEmpty());
    }

    /**
     * Review round 1, Important #3: StageWriteScopeGuard's new Task 15
     * {@code check(Case, Stage)} overload had zero test coverage -- every
     * other test in this class uses {@code journey.newCase}, which never
     * sets {@code currentStageId}, so {@code applyWriteScope} was a silent
     * no-op throughout. This uses a REAL case (through {@code CaseService.create},
     * which does run {@code CaseEngine.reconcile} and set {@code currentStageId})
     * pinned to a published one-stage workflow whose stage is {@code OWNER_ONLY},
     * the same construction {@code task.TaskWriteScopeTest.aWiderScopedHolderIsStillRefusedInsideAnOwnerOnlyStage}
     * uses. TEAM is the scope under test (CLAUDE.md: "at least one write test
     * must run at the narrowest [catalogued] scope" wherever a permission is
     * catalogued at several) -- the actor's TEAM membership DOES match the
     * case's own {@code owningTeamId}, so {@code document.upload}'s own
     * record-level scope resolves this case successfully; the refusal proven
     * here is specifically the write_scope guard narrowing ON TOP of that,
     * because the actor is not the case's OWNER.
     */
    @Test
    void aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("doc-ws-owner-only-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "team-scope+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            // WORKFLOW_VIEW is needed too -- applyWriteScope resolves the case's
            // current Stage under it (the same "viewing a case is gated by more
            // than case.view" shape CLAUDE.md already documents for CaseService),
            // and with none granted the Stage lookup itself 404s before the
            // write-scope check is even reached, the same way TaskWriteScopeTest's
            // analogous test grants it alongside TASK_MANAGE/TASK_COMPLETE.
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "doc-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () -> documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")))
                .isInstanceOf(WriteScopeException.class);

        fixture.runAs(tenant, () -> assertThat(documentRepository.findByCaseId(caseId[0])).isEmpty());
    }

    /**
     * Two callers append a version to the SAME document at the same moment.
     * document_version_no_uq is the truth, not a pre-check -- both threads
     * compute {@code maxVersionNo() + 1} before either commits (there is no
     * row lock the way CaseEngine.reconcile has one, because appending a
     * version derives no state), so both attempt version_no 2; the database
     * lets exactly one through and the other's insert fails, translated to
     * DocumentVersionConflictException rather than surfacing a raw
     * DataIntegrityViolationException (500) or silently overwriting anything.
     *
     * This also proves blob-first ordering's "no corruption" half: the loser
     * only ever reaches this exception from the try/catch around the row
     * insert in DocumentService.addVersion, which runs strictly AFTER
     * captureContent (the size check, the sniff and the blob write) has
     * already completed without error -- so the loser's blob was written to
     * the store before its row insert failed, exactly like any other
     * unreferenced-but-harmless orphan (spec §7.4), and the database is left
     * with precisely two version rows afterward, never three, never a
     * duplicate, never a partial one.
     */
    /**
     * A genuine database race with no lock serialising it (by design -- see
     * the method javadoc) has an inherently timing-dependent window: unlike
     * {@code ReconcileConcurrencyTest}'s row-locked race (where either
     * interleaving is provably correct), here the two attempts must
     * ACTUALLY overlap -- both reading {@code maxVersionNo} before either
     * commits -- for the constraint to have anything to refuse. If one
     * thread's connection is slower to acquire than the other's (a cold
     * HikariCP borrow, a GC pause), it can finish its entire transaction
     * before the second one even starts, and no collision occurs that
     * attempt -- not a defect, just no overlap. So this retries against a
     * FRESH document (version numbering restarts at 1 each time) until a
     * genuine collision is observed, capped at 20 attempts; failing to ever
     * observe one across 20 would itself indicate the race is not being
     * constructed correctly, which is why that absence is itself asserted
     * on below.
     */
    @Test
    void aSecondConcurrentVersionAtTheSameNumberIsA409NotASilentOverwrite() throws Exception {
        UUID tenant = fixture.createTenant("doc-race-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "race-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            boolean collided = false;
            for (int attempt = 0; attempt < 20 && !collided; attempt++) {
                var documentId = new UUID[1];
                fixture.runAsUser(tenant, actor[0], () -> documentId[0] = documents.upload(caseId[0],
                        new CreateDocumentRequest("Race Doc", DocumentCategory.OTHER,
                                VisibilityTier.COMPANY_SHARED, null, null, null, null),
                        new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());
                UUID theDocumentId = documentId[0];

                var barrier = new CyclicBarrier(2);
                Future<Object> a = pool.submit(() -> addVersionAfterBarrier(barrier, tenant, actor[0], theDocumentId));
                Future<Object> b = pool.submit(() -> addVersionAfterBarrier(barrier, tenant, actor[0], theDocumentId));
                Object resultA = a.get(30, TimeUnit.SECONDS);
                Object resultB = b.get(30, TimeUnit.SECONDS);

                long conflicts = List.of(resultA, resultB).stream()
                        .filter(DocumentVersionConflictException.class::isInstance).count();
                if (conflicts == 0) continue; // no genuine overlap this attempt -- try again on a fresh document

                long successes = List.of(resultA, resultB).stream()
                        .filter(DocumentVersionView.class::isInstance).count();
                assertThat(conflicts).isEqualTo(1);
                assertThat(successes).isEqualTo(1);

                fixture.runAs(tenant, () -> {
                    List<DocumentVersion> versions = versionRepository.findByDocumentId(theDocumentId);
                    assertThat(versions).hasSize(2);
                    assertThat(versions.stream().map(DocumentVersion::getVersionNo).sorted().toList())
                            .containsExactly(1, 2);
                });
                collided = true;
            }
            assertThat(collided)
                    .as("expected at least one of 20 concurrent attempts to genuinely race the same version_no")
                    .isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    /** Returns either the successful DocumentVersionView or the caught DocumentVersionConflictException. */
    private Object addVersionAfterBarrier(CyclicBarrier barrier, UUID tenant, UUID actor, UUID documentId) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        AtomicReference<DocumentVersionView> result = new AtomicReference<>();
        try {
            fixture.runAsUser(tenant, actor, () -> result.set(documents.addVersion(documentId,
                    new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf")));
            return result.get();
        } catch (DocumentVersionConflictException e) {
            return e;
        }
    }

    /**
     * Task 17: a plain rename/recategorise, no targeting field supplied.
     * Proves PATCH semantics -- fields left out (targetDepartmentId/
     * targetContactLabel both null on the request) are left exactly as they
     * were, never blanked -- and that a non-retargeting patch records no
     * document.retargeted event at all.
     */
    @Test
    void patchRenamesAndRecategorisesWithoutTouchingUnsuppliedFieldsOrAuditingARetarget() {
        UUID tenant = fixture.createTenant("doc-patch-rename-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var targetDept = new UUID[1];

        fixture.runAs(tenant, () -> {
            targetDept[0] = fixture.createDepartment(tenant, "Original Target " + Uuid7.generate());
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "patch-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
            documentId[0] = createDocument(tenant, c.getId(), c.getCustomerId(), manager[0], targetDept[0]);
        });

        AtomicReference<DocumentView> patched = new AtomicReference<>();
        fixture.runAsUser(tenant, manager[0], () -> patched.set(documents.patch(documentId[0],
                new PatchDocumentRequest("Renamed Document", DocumentCategory.INVOICE, null, null))));

        assertThat(patched.get().name()).isEqualTo("Renamed Document");
        assertThat(patched.get().category()).isEqualTo(DocumentCategory.INVOICE);
        // Untouched: neither targeting field was supplied on the request.
        assertThat(patched.get().targetDepartmentId()).isEqualTo(targetDept[0]);
        assertThat(patched.get().targetContactLabel()).isNull();

        fixture.runAs(tenant, () -> assertThat(auditEvents.findAll())
                .extracting(AuditEvent::getAction)
                .doesNotContain("document.retargeted"));
    }

    /**
     * The recovery case, spec Sec 6.4's entire justification for
     * {@code document.manage} not being narrowed by {@link
     * co.ara.onboarding.scoping.DocumentAudienceFilter}: a document targeted
     * at a department with NO current members is invisible to every internal
     * actor under {@code document.view} -- including an ALL-scoped one, since
     * targeting binds everyone (Sec 6.4's own words) -- but still loadable
     * and retargetable through {@code document.manage}. Proves the full
     * round trip: unreachable via {@code get()} before, retargeted via
     * {@code patch()}, reachable via {@code get()} again afterward for an
     * actor who is now a real member of the NEW target department -- and
     * that the retarget itself is audited as its own, compliance-only
     * action (document.retargeted, timelineVisible=false -- Task 17's own
     * ruling, matching the Task 29 plan section's reasoning).
     */
    @Test
    void patchRetargetsADocumentOutOfAnEmptyDepartmentRecoveringVisibility() {
        UUID tenant = fixture.createTenant("doc-patch-recovery-" + Uuid7.generate());
        var emptyDept = new UUID[1];
        var populatedDept = new UUID[1];
        var manager = new UUID[1];
        var viewerInPopulatedDept = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            emptyDept[0] = fixture.createDepartment(tenant, "Empty Legal " + Uuid7.generate());
            populatedDept[0] = fixture.createDepartment(tenant, "Populated Finance " + Uuid7.generate());
            Case c = journey.newCase(tenant);

            // No department of their own -- isolates the audience filter's
            // department-targeting narrowing from any department-scope
            // narrowing DocumentDescriptor would otherwise also apply.
            manager[0] = fixture.createUser(tenant, "patch-recovery-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            viewerInPopulatedDept[0] = fixture.createUserInDepartment(tenant,
                    "patch-recovery-viewer+" + Uuid7.generate() + "@example.com", populatedDept[0]);
            grant(viewerInPopulatedDept[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            documentId[0] = createDocument(tenant, c.getId(), c.getCustomerId(), manager[0], emptyDept[0]);
        });

        // Stuck: targeted at a department with zero members, so document.view
        // -- even ALL-scoped -- resolves nobody, the manage holder included.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () -> documents.get(documentId[0])))
                .isInstanceOf(NoSuchElementException.class);

        // Not stuck for document.manage: the audience filter returns
        // conjunction() for it, so the manage holder can load it to fix it.
        AtomicReference<DocumentView> patched = new AtomicReference<>();
        fixture.runAsUser(tenant, manager[0], () -> patched.set(documents.patch(documentId[0],
                new PatchDocumentRequest(null, null, populatedDept[0], null))));
        assertThat(patched.get().targetDepartmentId()).isEqualTo(populatedDept[0]);

        // Recovered: a real member of the NEW target department can see it now.
        fixture.runAsUser(tenant, viewerInPopulatedDept[0], () ->
                assertThat(documents.get(documentId[0]).id()).isEqualTo(documentId[0]));

        fixture.runAs(tenant, () -> {
            List<AuditEvent> retargeted = auditEvents.findAll().stream()
                    .filter(e -> "document.retargeted".equals(e.getAction()))
                    .toList();
            assertThat(retargeted).extracting(AuditEvent::getResourceId).containsExactly(documentId[0]);
            assertThat(retargeted).allMatch(e -> !e.isTimelineVisible());

            // Task 17 fix round: the finding was that this event recorded
            // neither the old nor the new target, so a second retarget would
            // make the department history unreconstructable from the
            // append-only log alone (spec 6.4). Assert the payload actually
            // carries both ends of this move, not just that the event exists.
            assertThat(retargeted.get(0).getPayload())
                    .contains("\"fromDepartmentId\": \"" + emptyDept[0] + "\"")
                    .contains("\"toDepartmentId\": \"" + populatedDept[0] + "\"");
        });
    }

    /** The write-path invariant applied to PATCH: a cross-tenant id is a 404, never a 500. */
    @Test
    void patchOfADocumentInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("doc-patch-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-patch-tenant-b-" + Uuid7.generate());
        var documentId = new UUID[1];
        var managerB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "patch-cross-uploader+" + Uuid7.generate() + "@example.com");
            documentId[0] = createDocument(tenantA, c, uploader);
        });

        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "patch-cross-manager+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () ->
                documents.patch(documentId[0], new PatchDocumentRequest("New Name", null, null, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The {@code write_scope} guard applies to PATCH exactly as it does to
     * {@code upload}/{@code addVersion} (CLAUDE.md: "a new mutation against a
     * case's stage calls through it rather than re-deriving the check") --
     * a TEAM-scoped document.manage holder, matching the case's own team, is
     * still refused inside an OWNER_ONLY stage when they are not the case's
     * owner. TEAM is deliberately the scope under test (CLAUDE.md: "at least
     * one write test must run at the narrowest [catalogued] scope"), the
     * same construction {@code aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage}
     * above uses for upload.
     */
    @Test
    void aTeamScopedManageHolderIsStillRefusedInsideAnOwnerOnlyStageWhenPatching() {
        UUID tenant = fixture.createTenant("doc-patch-ws-owner-only-" + Uuid7.generate());
        var teamScopeNonOwner = new UUID[1];
        var documentId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopeNonOwner[0] = fixture.createUser(tenant, "patch-team-scope+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopeNonOwner[0], team);
            grant(teamScopeNonOwner[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "patch-doc-owner+" + Uuid7.generate() + "@example.com");

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Doc Patch Write Scope Co " + Uuid7.generate(), caseOwner, null, team);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            documentId[0] = createDocument(tenant, caseId, customerId, caseOwner, null);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, teamScopeNonOwner[0], () -> documents.patch(
                documentId[0], new PatchDocumentRequest("New Name", null, null, null))))
                .isInstanceOf(WriteScopeException.class);
    }

    /**
     * Task 18, design spec 5.5 point 1: retiring revokes every LIVE
     * {@link DocumentShare} for the document -- a share already revoked
     * before retirement is left exactly as it was, never re-stamped with a
     * new {@code revokedAt}.
     */
    @Test
    void retiringRevokesEveryLiveShare() {
        UUID tenant = fixture.createTenant("doc-retire-shares-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var liveShareId = new UUID[1];
        var alreadyRevokedShareId = new UUID[1];
        var alreadyRevokedAt = new Instant[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "retire-share-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
            documentId[0] = createDocument(tenant, c, manager[0]);

            DocumentShare live = new DocumentShare(Uuid7.generate(), tenant, documentId[0],
                    SharePrincipalType.USER, Uuid7.generate(), manager[0], Instant.now(clock));
            shareRepository.saveAndFlush(live);
            liveShareId[0] = live.getId();

            DocumentShare alreadyRevoked = new DocumentShare(Uuid7.generate(), tenant, documentId[0],
                    SharePrincipalType.CONTACT, Uuid7.generate(), manager[0], Instant.now(clock));
            // Truncated to microseconds -- Postgres timestamptz precision -- so
            // the equality check below survives the DB round trip.
            alreadyRevokedAt[0] = Instant.now(clock).minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            alreadyRevoked.setRevokedAt(alreadyRevokedAt[0]);
            shareRepository.saveAndFlush(alreadyRevoked);
            alreadyRevokedShareId[0] = alreadyRevoked.getId();
        });

        fixture.runAsUser(tenant, manager[0], () -> documents.retire(documentId[0], "Wrong file uploaded"));

        fixture.runAs(tenant, () -> {
            DocumentShare live = shareRepository.findById(liveShareId[0]).orElseThrow();
            assertThat(live.getRevokedAt()).isNotNull();

            DocumentShare untouched = shareRepository.findById(alreadyRevokedShareId[0]).orElseThrow();
            assertThat(untouched.getRevokedAt()).isEqualTo(alreadyRevokedAt[0]);
        });
    }

    /**
     * Task 18, design spec 5.5 point 2: retiring revokes every LIVE
     * {@link DocumentCaseLink} for the document, the same "unlink is a
     * column, not a DELETE" shape as shares -- and an already-revoked link
     * is left untouched, the same idempotence proven above for shares.
     */
    @Test
    void retiringRevokesEveryCrossJourneyLink() {
        UUID tenant = fixture.createTenant("doc-retire-links-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];
        var liveLinkId = new UUID[1];
        var alreadyRevokedLinkId = new UUID[1];
        var alreadyRevokedAt = new Instant[1];

        fixture.runAs(tenant, () -> {
            Case home = journey.newCase(tenant);
            Case other = journey.newCase(tenant);
            manager[0] = fixture.createUser(tenant, "retire-link-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));
            documentId[0] = createDocument(tenant, home, manager[0]);

            DocumentCaseLink live = new DocumentCaseLink(
                    Uuid7.generate(), tenant, documentId[0], other.getId(), manager[0], Instant.now(clock));
            linkRepository.saveAndFlush(live);
            liveLinkId[0] = live.getId();

            Case yetAnother = journey.newCase(tenant);
            DocumentCaseLink alreadyRevoked = new DocumentCaseLink(
                    Uuid7.generate(), tenant, documentId[0], yetAnother.getId(), manager[0], Instant.now(clock));
            alreadyRevokedAt[0] = Instant.now(clock).minusSeconds(60).truncatedTo(ChronoUnit.MICROS);
            alreadyRevoked.setRevokedAt(alreadyRevokedAt[0]);
            linkRepository.saveAndFlush(alreadyRevoked);
            alreadyRevokedLinkId[0] = alreadyRevoked.getId();
        });

        fixture.runAsUser(tenant, manager[0], () -> documents.retire(documentId[0], "Wrong file uploaded"));

        fixture.runAs(tenant, () -> {
            DocumentCaseLink live = linkRepository.findById(liveLinkId[0]).orElseThrow();
            assertThat(live.getRevokedAt()).isNotNull();

            DocumentCaseLink untouched = linkRepository.findById(alreadyRevokedLinkId[0]).orElseThrow();
            assertThat(untouched.getRevokedAt()).isEqualTo(alreadyRevokedAt[0]);
        });
    }

    /**
     * Task 18, design spec 5.5 point 3 -- the one that distinguishes
     * retirement from sub-project 3's task-cancellation rule (a task is
     * cancelled BEFORE it satisfies; a document is realistically retired
     * AFTER it satisfied -- the wrong file was uploaded), and the easiest of
     * the four to omit. Goes through the gated
     * {@code journey.RequirementService.reopen}, never a direct write to
     * requirement state -- proven here by the {@link Requirement} row itself
     * reading OPEN again with every satisfying field cleared, and by the
     * milestone's OWN {@code progressPercent} dropping back below 100 --
     * both only reachable through a real {@code CaseEngine.reconcile}.
     *
     * Deliberately NOT asserted: the milestone's {@code status} reverting off
     * DONE. {@code CaseEngine.recomputeStatusesAndProgress}'s own comment
     * documents DONE as STICKY, same as SKIPPED -- once a milestone reads
     * DONE, {@code reconcile} never re-evaluates its status again (Task 17's
     * force-complete relies on exactly this so its own reconcile call cannot
     * silently undo it). That stickiness is a pre-existing, deliberate
     * property of {@code CaseEngine} this task does not touch and this plan
     * explicitly forbids modifying ("CaseEngine is not modified anywhere in
     * this plan" -- if a task appears to need an engine change, escalate
     * rather than make it) -- so a milestone a retired document had
     * completed keeps reading DONE even after its requirement reopens,
     * while its own progress number quietly disagrees. Recorded here as a
     * real, known gap for a later sub-project rather than worked around.
     *
     * There is no real document-satisfies-requirement wiring yet (Tasks
     * 24/25), so the SATISFIED requirement is seeded directly through
     * {@code RequirementService.satisfy}, using {@code "document"}
     * (lowercase) as {@code satisfiedRefType} -- this sub-project's own
     * convention, matching {@code task.TaskService}'s existing {@code "task"}
     * call site, that Tasks 24/25 must reuse rather than inventing a second
     * string for the same concept ({@link DocumentService#SATISFIED_REF_TYPE}).
     */
    @Test
    void retiringReopensARequirementItSatisfied() {
        UUID tenant = fixture.createTenant("doc-retire-reopen-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Retire Reopen Co " + Uuid7.generate(), null, null, null);
            // publishedTemplate()'s single-stage/single-milestone/single-requirement
            // shape would complete the WHOLE CASE the moment this one requirement
            // is satisfied -- CaseEngine.reconcile's own top-of-method guard then
            // short-circuits on CaseStatus.COMPLETED and never runs again, hiding
            // whatever reopen() does. publishedThreeStageWorkflow() gives stage
            // one a SECOND milestone that stays outstanding, so satisfying the
            // first milestone's requirement leaves the case genuinely ACTIVE and
            // a real reconcile still has something to recompute after reopening.
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            manager[0] = fixture.createUser(tenant, "retire-reopen-manager+" + Uuid7.generate() + "@example.com");
            // WORKFLOW_VIEW is needed too -- applyWriteScope resolves the
            // case's current Stage under it, and with none granted the Stage
            // lookup itself 404s before retire's own body ever runs (the
            // same shape aTeamScopedHolderIsStillRefusedInsideAnOwnerOnlyStage
            // above documents for upload/patch).
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0], null);

            requirements.satisfy(requirementId[0], documentId[0], DocumentService.SATISFIED_REF_TYPE);
        });

        fixture.runAs(tenant, () -> {
            var milestone = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0);
            assertThat(milestone.status()).isEqualTo(MilestoneStatus.DONE);
        });

        fixture.runAsUser(tenant, manager[0], () -> documents.retire(documentId[0], "Wrong file uploaded"));

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
            assertThat(r.getSatisfiedRefType()).isNull();
            assertThat(r.getSatisfiedAt()).isNull();
            assertThat(r.getSatisfiedBy()).isNull();

            // The engine DID run (reconcile recomputes this unconditionally,
            // unlike status -- see this test's own javadoc): the milestone's
            // own weighted percent drops back below 100 once its requirement
            // is unsettled again, even though status itself stays DONE.
            var milestone = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0);
            assertThat(milestone.progressPercent()).isLessThan(100);

            // Fix round Finding 1: AuditActions.REQUIREMENT_REOPENED was added
            // and RequirementService.reopen records it, but nothing had ever
            // read it back -- the requirement/milestone assertions above prove
            // the STATE changed, not that the event exists. Same shape as
            // patchRetargetsADocumentOutOfAnEmptyDepartmentRecoveringVisibility's
            // own audit assertion above: filter by action, assert resourceId
            // and that the payload actually names the requirement/milestone
            // reopened, not merely that some event with this action exists.
            List<AuditEvent> reopened = auditEvents.findAll().stream()
                    .filter(e -> "requirement.reopened".equals(e.getAction()))
                    .toList();
            assertThat(reopened).hasSize(1);
            AuditEvent event = reopened.get(0);
            assertThat(event.getResourceId()).isEqualTo(caseId[0]);
            assertThat(event.getResourceType()).isEqualTo("onboarding_case");
            assertThat(event.isTimelineVisible()).isTrue();
            assertThat(event.getPayload())
                    .contains(requirementId[0].toString())
                    .contains(r.getMilestoneId().toString());
        });
    }

    /**
     * Task 18, design spec 5.5 point 4: bytes are never deleted -- there is
     * no {@code BlobStore.delete} to call in the first place (spec 7.1).
     * Proven by successfully re-reading the exact same content after
     * retirement, not merely by the absence of a delete call.
     */
    @Test
    void retiringLeavesTheBlobReadableByKey() {
        UUID tenant = fixture.createTenant("doc-retire-blob-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            manager[0] = fixture.createUser(tenant, "retire-blob-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL));
        });

        fixture.runAsUser(tenant, manager[0], () -> documentId[0] = documents.upload(caseId[0],
                new CreateDocumentRequest("Doc", DocumentCategory.OTHER, VisibilityTier.COMPANY_SHARED,
                        null, null, null, null),
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf").id());

        fixture.runAsUser(tenant, manager[0], () -> documents.retire(documentId[0], "Wrong file uploaded"));

        fixture.runAsUser(tenant, manager[0], () -> {
            try {
                BlobContent blob = content.open(documentId[0], 1);
                assertThat(blob.content().readAllBytes()).isEqualTo(PDF_BYTES);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * The gap Task 14's own review deferred to this task: {@link
     * DocumentService#list}/{@link DocumentService#forCase} must both
     * exclude a RETIRED document, not merely leave it out of {@link
     * DocumentService#get}'s own reach -- get() stays retrievable by id on
     * purpose, since a retired document is still viewable, just not listed.
     */
    @Test
    void aRetiredDocumentIsNotReturnedByAnyListing() {
        UUID tenant = fixture.createTenant("doc-retire-listing-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var activeDocId = new UUID[1];
        var retiredDocId = new UUID[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            caseId[0] = c.getId();
            manager[0] = fixture.createUser(tenant, "retire-listing-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_VIEW, Scope.ALL,
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL));

            activeDocId[0] = createDocument(tenant, c, manager[0]);
            retiredDocId[0] = createDocument(tenant, c, manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () -> documents.retire(retiredDocId[0], "Wrong file uploaded"));

        fixture.runAsUser(tenant, manager[0], () -> {
            assertThat(documents.list(null, Pageable.unpaged()).getContent())
                    .extracting(DocumentView::id).containsExactly(activeDocId[0]);
            assertThat(documents.forCase(caseId[0], Pageable.unpaged()).getContent())
                    .extracting(DocumentView::id).containsExactly(activeDocId[0]);
        });

        // get() stays retrievable by id -- a retired document is still
        // viewable, just not listed (DocumentService.retire's own javadoc).
        fixture.runAsUser(tenant, manager[0], () ->
                assertThat(documents.get(retiredDocId[0]).status()).isEqualTo(DocumentStatus.RETIRED));
    }

    /**
     * Task 18 fix round, Finding 2: CLAUDE.md's working convention --
     * "wherever a permission is catalogued at several scopes, at least one
     * write test must run at the narrowest one" (named because every write
     * case in {@code UserAdminTest} granted {@code USER_MANAGE} at ALL, which
     * is precisely why that escalation survived undetected) -- applied to
     * {@code RequirementService.reopen}'s own re-resolution, since every one
     * of Task 18's five original tests granted {@code MILESTONE_COMPLETE} at
     * {@code Scope.ALL}.
     *
     * {@code MILESTONE_COMPLETE} is RECORD-scoped ({@code PermissionCatalog}:
     * {@code RECORD = EnumSet.allOf(Scope.class)}); {@code ASSIGNED} is its
     * narrowest, resolving through a real {@link co.ara.onboarding.journey.CaseParticipant}
     * row ({@code scoping.RequirementDescriptor.assignedScope}) -- the same
     * shape {@code scoping.JourneyScopingTest
     * .completingAMilestoneAtAssignedScopeIsRefusedForSomeoneElsesCase}
     * already proves for a direct {@code satisfy()} call.
     *
     * The manager here holds {@code document.manage} at ALL (so retire's own
     * gate, and its Document/Case resolution, succeed) and
     * {@code workflow.view} at ALL (so {@code applyWriteScope}'s Stage lookup
     * succeeds), but {@code milestone.complete} only at ASSIGNED -- and is
     * never added as a {@code CaseParticipant} on the case whose requirement
     * the document satisfied. The requirement is satisfied here as the
     * fixture's own administrator ({@code fixture.runAs}, not {@code manager}),
     * so the refusal below cannot be a side effect of the satisfying call
     * itself. The method-level {@code @RequirePermission(MILESTONE_COMPLETE)}
     * on {@code reopen} lets {@code manager} in regardless of scope (it can
     * only see that they hold the permission at SOME scope, not which
     * record) -- the refusal proven here is {@code reopen}'s own
     * re-resolution of the {@link Requirement} through {@code AuthorizedQuery},
     * exactly the write-path half CLAUDE.md warns "keeps escaping" when
     * untested at the narrowest scope. Nothing is revoked afterward either --
     * the whole {@code retire} transaction rolled back, not just the reopen
     * call.
     */
    @Test
    void retireIsRefusedWhenTheActorsMilestoneCompleteGrantDoesNotCoverTheCase() {
        UUID tenant = fixture.createTenant("doc-retire-narrow-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Retire Narrow Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            manager[0] = fixture.createUser(tenant, "retire-narrow-manager+" + Uuid7.generate() + "@example.com");
            // ASSIGNED, deliberately NOT ALL -- MILESTONE_COMPLETE's narrowest
            // catalogued scope. manager is never added as a CaseParticipant
            // on caseId[0], so RequirementDescriptor/MilestoneDescriptor's
            // assignedScope resolves nothing for them.
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ASSIGNED,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0], null);
            requirements.satisfy(requirementId[0], documentId[0], DocumentService.SATISFIED_REF_TYPE);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                documents.retire(documentId[0], "Wrong file uploaded")))
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.SATISFIED);

            Document d = documentRepository.findById(documentId[0]).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        });
    }

    /**
     * Task 18 fix round, Finding 4: {@code DocumentService.retire}'s own
     * javadoc now documents this interaction explicitly -- proven live here
     * rather than left as an implicit consequence of two independently-tested
     * mechanisms. {@code RequirementTest} already proves a direct
     * {@code satisfy()} call refuses with {@link CaseOnHoldException} against
     * an {@code ON_HOLD} case; this proves that {@code retire} calling
     * {@code reopen} INSIDE its own {@code @Transactional} method (Spring's
     * default REQUIRED propagation joins the same transaction, it does not
     * nest one) means that same refusal rolls back retire's OWN share/link
     * revocations too, not just reopen's write -- proven by asserting the
     * previously-live share is STILL live afterward, not merely that the
     * method throws. Nothing is revoked; the document stays ACTIVE. This is
     * the documented, deliberately-not-fixed behaviour: revoking access to a
     * wrongly-uploaded document is most urgent exactly when the case is
     * already ON_HOLD, and yet this fails completely until the hold clears --
     * changing that is a bigger decision about hold semantics than this fix
     * round makes.
     */
    @Test
    void retireOfADocumentWhoseSatisfiedRequirementIsOnAnOnHoldCaseRevokesNothing() {
        UUID tenant = fixture.createTenant("doc-retire-onhold-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var documentId = new UUID[1];
        var liveShareId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Retire OnHold Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            manager[0] = fixture.createUser(tenant, "retire-onhold-manager+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_MANAGE, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            documentId[0] = createDocument(tenant, caseId[0], customerId, manager[0], null);
            requirements.satisfy(requirementId[0], documentId[0], DocumentService.SATISFIED_REF_TYPE);

            DocumentShare live = new DocumentShare(Uuid7.generate(), tenant, documentId[0],
                    SharePrincipalType.USER, Uuid7.generate(), manager[0], Instant.now(clock));
            shareRepository.saveAndFlush(live);
            liveShareId[0] = live.getId();

            cases.hold(caseId[0], "Waiting on legal");
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                documents.retire(documentId[0], "Wrong file uploaded")))
                .isInstanceOf(CaseOnHoldException.class);

        fixture.runAs(tenant, () -> {
            // The whole transaction rolled back -- not just reopen's own write.
            DocumentShare share = shareRepository.findById(liveShareId[0]).orElseThrow();
            assertThat(share.getRevokedAt()).isNull();

            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.SATISFIED);

            Document d = documentRepository.findById(documentId[0]).orElseThrow();
            assertThat(d.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        });
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createDocument(UUID tenant, Case c, UUID uploadedBy) {
        return createDocument(tenant, c.getId(), c.getCustomerId(), uploadedBy, null);
    }

    /** Overload used by Task 17's tests to seed a document already targeted at a department. */
    private UUID createDocument(UUID tenant, UUID caseId, UUID customerId, UUID uploadedBy, UUID targetDepartmentId) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(caseId);
        d.setCustomerId(customerId);
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setTargetDepartmentId(targetDepartmentId);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
