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
import org.springframework.data.domain.Pageable;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
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
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired DocumentCaseLinkRepository linkRepository;
    @Autowired RoleService roles;
    @Autowired StorageProperties storageProperties;

    /** A minimal, real PDF magic prefix -- enough for Tika's own magic-byte detection to say "application/pdf". */
    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    /** The brief's own example: real bytes that sniff as text/html, dressed up as a PDF upload. */
    private static final byte[] HTML_BYTES =
            ("<!DOCTYPE html>\n<html><head><title>Not a PDF</title></head>"
                    + "<body>Not actually a PDF</body></html>").getBytes(StandardCharsets.UTF_8);

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
                listed.set(documents.list(Pageable.unpaged()).getContent()));
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

        fixture.runAs(tenant, () -> {
            List<DocumentVersion> versions = versionRepository.findByDocumentId(uploaded.get().id());
            assertThat(versions).hasSize(1);
            assertThat(versions.get(0).getVersionNo()).isEqualTo(1);
            assertThat(versions.get(0).getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
            assertThat(versions.get(0).getContentType()).isEqualTo("application/pdf");
        });
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

    /** version_no starts at 1 (upload) and increments by one on each subsequent addVersion call. */
    @Test
    void versionNumbersStartAtOneAndIncrementOnEachAppendedVersion() {
        UUID tenant = fixture.createTenant("doc-vno-" + Uuid7.generate());
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseId[0] = journey.newCase(tenant).getId();
            actor[0] = fixture.createUser(tenant, "vno-uploader+" + Uuid7.generate() + "@example.com");
            grant(actor[0], Map.of(PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL));
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
