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
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
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
    @Autowired DocumentCaseLinkRepository linkRepository;
    @Autowired RoleService roles;

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
