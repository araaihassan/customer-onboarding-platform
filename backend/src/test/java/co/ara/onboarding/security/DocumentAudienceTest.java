package co.ara.onboarding.security;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCategory;
import co.ara.onboarding.document.DocumentRepository;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.document.DocumentShareRepository;
import co.ara.onboarding.document.DocumentStatus;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.document.VisibilityTier;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.scoping.DocumentAudienceFilter;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DocumentAudienceFilter}'s internal branch (design spec 6.3/6.4) -- the
 * first real use of the {@code AudienceFilter} mechanism Task 2 built. Targeting
 * binds EVERYONE, including an actor holding {@code document.view} at ALL: that
 * is the whole reason this is an audience filter rather than another department
 * scope predicate, which {@code AuthorizationPredicateBuilder} short-circuits
 * past the moment ALL is held. Every positive test below therefore grants
 * {@code document.view} at ALL ({@link TenantFixture#grantAtAllScope}) -- a
 * narrower-scoped test would pass against a broken or absent filter and prove
 * nothing (spec 6.2's own warning, echoed in {@code AudienceFilterTest}).
 *
 * Only the internal branch is exercised here. Task 13 adds the portal half to
 * the same class.
 */
class DocumentAudienceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentRepository documents;
    @Autowired DocumentShareRepository documentShares;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired DocumentAudienceFilter filter;

    /**
     * The test that matters most. An ALL-scoped document.view holder -- the
     * tenant's own Administrator, in shape -- must still be refused a document
     * targeted at a department they are not in. Run this first and confirm it
     * fails with the filter absent: that failure is what proves the mechanism is
     * doing anything at all.
     */
    @Test
    void anAdministratorAtAllScopeCannotReadALegalTargetedDocument() {
        UUID tenant = fixture.createTenant("doc-aud-all");
        var adminRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            adminRef.set(fixture.createUser(tenant, "admin@doc-aud-all.example"));
            Case c = journey.newCase(tenant);
            docRef.set(newTargetedDocument(tenant, c, adminRef.get(), legal));
        });
        fixture.grantAtAllScope(tenant, adminRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, adminRef.get(), () -> {
            var page = authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged());
            assertThat(page.getContent())
                    .as("an ALL-scoped holder must still be refused a Legal-targeted document")
                    .isEmpty();

            assertThatThrownBy(() -> authorizedQuery.getById(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, docRef.get()))
                    .as("out-of-audience is a 404-shaped NoSuchElementException, never a silent read")
                    .isInstanceOf(NoSuchElementException.class);
        });
    }

    @Test
    void anUntargetedDocumentIsVisibleToAnyoneWithTheScope() {
        UUID tenant = fixture.createTenant("doc-aud-untargeted");
        var userRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            userRef.set(fixture.createUser(tenant, "reader@doc-aud-untargeted.example"));
            Case c = journey.newCase(tenant);
            docRef.set(newDocument(tenant, c, userRef.get(), null));
        });
        fixture.grantAtAllScope(tenant, userRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, userRef.get(), () -> {
            var page = authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged());
            assertThat(page.getContent())
                    .as("no targeting means no audience narrowing")
                    .extracting(Document::getId).containsExactly(docRef.get());
        });
    }

    @Test
    void anExplicitShareToMyDepartmentWidensPastTargeting() {
        UUID tenant = fixture.createTenant("doc-aud-share");
        var userRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            UUID finance = fixture.createDepartment(tenant, "Finance");
            UUID granter = fixture.createUser(tenant, "granter@doc-aud-share.example");
            userRef.set(fixture.createUserInDepartment(tenant, "financeuser@doc-aud-share.example", finance));
            Case c = journey.newCase(tenant);
            docRef.set(newTargetedDocument(tenant, c, granter, legal));

            documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, docRef.get(),
                    SharePrincipalType.DEPARTMENT, finance, granter, Instant.now()));
        });
        fixture.grantAtAllScope(tenant, userRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, userRef.get(), () -> {
            var page = authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged());
            assertThat(page.getContent())
                    .as("an explicit department share widens past a targeting mismatch")
                    .extracting(Document::getId).containsExactly(docRef.get());
        });
    }

    /**
     * The DEPARTMENT-principal share test above never proves revocation actually
     * does anything -- {@code revokedAt} could be ignored entirely and that test
     * would still pass. Revoke the same share after confirming it widens, and
     * confirm the document goes back to being refused.
     */
    @Test
    void aRevokedShareNoLongerWidensTheAudience() {
        UUID tenant = fixture.createTenant("doc-aud-revoke");
        var userRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var shareRef = new java.util.concurrent.atomic.AtomicReference<DocumentShare>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            UUID finance = fixture.createDepartment(tenant, "Finance");
            UUID granter = fixture.createUser(tenant, "granter@doc-aud-revoke.example");
            userRef.set(fixture.createUserInDepartment(tenant, "financeuser@doc-aud-revoke.example", finance));
            Case c = journey.newCase(tenant);
            docRef.set(newTargetedDocument(tenant, c, granter, legal));

            shareRef.set(documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, docRef.get(),
                    SharePrincipalType.DEPARTMENT, finance, granter, Instant.now())));
        });
        fixture.grantAtAllScope(tenant, userRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, userRef.get(), () -> {
            assertThat(authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                    .as("the live share widens past targeting, same as the sibling test")
                    .extracting(Document::getId).containsExactly(docRef.get());
        });

        fixture.runAs(tenant, () -> {
            shareRef.get().setRevokedAt(Instant.now());
            documentShares.saveAndFlush(shareRef.get());
        });

        fixture.runAsUser(tenant, userRef.get(), () -> {
            assertThat(authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                    .as("a revoked share must no longer widen the audience -- targeting reasserts itself")
                    .isEmpty();
        });
    }

    /**
     * The DEPARTMENT-principal share is exercised above; the USER-principal
     * disjunct has no test of its own yet. Share to one specific user and prove
     * a second user -- otherwise identically placed -- does NOT gain access:
     * this is a personal grant, not a department-wide one.
     */
    @Test
    void anExplicitUserShareWidensOnlyForThatSpecificUser() {
        UUID tenant = fixture.createTenant("doc-aud-user-share");
        var grantedUserRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var otherUserRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            UUID granter = fixture.createUser(tenant, "granter@doc-aud-user-share.example");
            grantedUserRef.set(fixture.createUser(tenant, "granted@doc-aud-user-share.example"));
            otherUserRef.set(fixture.createUser(tenant, "notgranted@doc-aud-user-share.example"));
            Case c = journey.newCase(tenant);
            docRef.set(newTargetedDocument(tenant, c, granter, legal));

            documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, docRef.get(),
                    SharePrincipalType.USER, grantedUserRef.get(), granter, Instant.now()));
        });
        fixture.grantAtAllScope(tenant, grantedUserRef.get(), PermissionKeys.DOCUMENT_VIEW);
        fixture.grantAtAllScope(tenant, otherUserRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, grantedUserRef.get(), () -> {
            assertThat(authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                    .as("the named user's explicit share widens past targeting")
                    .extracting(Document::getId).containsExactly(docRef.get());
        });

        fixture.runAsUser(tenant, otherUserRef.get(), () -> {
            assertThat(authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                    .as("a USER share is personal -- a different user, otherwise identically placed, must not match")
                    .isEmpty();
        });
    }

    /**
     * The single most important test this task was missing. sharedWith's EXISTS
     * subquery correlates on {@code share.documentId = root.id}; nothing above
     * proves that correlation actually holds. An uncorrelated subquery -- "any
     * live share to this principal, on ANY document" -- would make every one of
     * the tests above pass just as well, while actually leaking every OTHER
     * targeted document in the tenant to a principal shared on just one of them.
     * Two targeted documents, one shared: the principal must see only the one
     * actually named in a document_share row for them.
     */
    @Test
    void theShareSubqueryIsCorrelatedToItsOwnDocumentNotAnyDocumentThePrincipalWasEverSharedOn() {
        UUID tenant = fixture.createTenant("doc-aud-correlation");
        var userRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var sharedDocRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var unsharedDocRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            UUID granter = fixture.createUser(tenant, "granter@doc-aud-correlation.example");
            userRef.set(fixture.createUser(tenant, "reader@doc-aud-correlation.example"));
            Case c = journey.newCase(tenant);
            sharedDocRef.set(newTargetedDocument(tenant, c, granter, legal));
            unsharedDocRef.set(newTargetedDocument(tenant, c, granter, legal));

            // Shares only the FIRST document -- the second is targeted at the
            // same department and never shared at all.
            documentShares.saveAndFlush(new DocumentShare(Uuid7.generate(), tenant, sharedDocRef.get(),
                    SharePrincipalType.USER, userRef.get(), granter, Instant.now()));
        });
        fixture.grantAtAllScope(tenant, userRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, userRef.get(), () -> {
            assertThat(authorizedQuery.findAll(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, null, Pageable.unpaged()).getContent())
                    .as("a share on ONE document must never widen an unrelated document in the same tenant -- "
                            + "an uncorrelated EXISTS would leak the second document here too")
                    .extracting(Document::getId).containsExactly(sharedDocRef.get());
        });
    }

    /**
     * document.manage is deliberately NOT narrowed -- otherwise a mis-targeted
     * document becomes permanently unreachable and unfixable (spec 6.4).
     */
    @Test
    void documentManageLoadsATargetedDocumentThatDocumentViewCannotRead() {
        UUID tenant = fixture.createTenant("doc-aud-manage");
        var adminRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            adminRef.set(fixture.createUser(tenant, "admin@doc-aud-manage.example"));
            Case c = journey.newCase(tenant);
            docRef.set(newTargetedDocument(tenant, c, adminRef.get(), legal));
        });
        fixture.grantAtAllScope(tenant, adminRef.get(), PermissionKeys.DOCUMENT_MANAGE);
        fixture.grantAtAllScope(tenant, adminRef.get(), PermissionKeys.DOCUMENT_VIEW);

        fixture.runAsUser(tenant, adminRef.get(), () -> {
            Document loaded = authorizedQuery.getById(documents, Document.class,
                    PermissionKeys.DOCUMENT_MANAGE, docRef.get());
            assertThat(loaded.getId())
                    .as("document.manage must load a targeted document to retarget it")
                    .isEqualTo(docRef.get());

            assertThatThrownBy(() -> authorizedQuery.getById(documents, Document.class,
                    PermissionKeys.DOCUMENT_VIEW, docRef.get()))
                    .as("the SAME actor, keyed on document.view instead, is still refused")
                    .isInstanceOf(NoSuchElementException.class);
        });
    }

    /**
     * ...but metadata is not bytes. The manage holder still cannot download.
     * Task 16 builds the real content endpoint (gated document.view); it does not
     * exist yet, so this asserts the scope-predicate difference the filter itself
     * produces for the two permission keys, rather than an end-to-end download.
     */
    @Test
    void documentManageCannotReadTheContentOfATargetedDocument() {
        UUID tenant = fixture.createTenant("doc-aud-content");
        var userRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var otherDeptRef = new java.util.concurrent.atomic.AtomicReference<UUID>();
        var docRef = new java.util.concurrent.atomic.AtomicReference<Document>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            otherDeptRef.set(fixture.createDepartment(tenant, "Other"));
            userRef.set(fixture.createUserInDepartment(tenant, "reader@doc-aud-content.example", otherDeptRef.get()));
            Case c = journey.newCase(tenant);
            UUID docId = newTargetedDocument(tenant, c, userRef.get(), legal);
            docRef.set(documents.findById(docId).orElseThrow());
        });

        // A REAL, non-null, MISMATCHED department -- not the null-departmentId
        // fail-closed path every other negative test above rides. This is what
        // actually exercises the targetDepartmentId != ctx.departmentId()
        // comparison rather than the "no department at all" short-circuit.
        AuthContext ctx = new AuthContext(tenant, userRef.get(), UserType.INTERNAL,
                otherDeptRef.get(), Set.of());

        var manageSpec = filter.audience(ctx, PermissionKeys.DOCUMENT_MANAGE);
        var viewSpec = filter.audience(ctx, PermissionKeys.DOCUMENT_VIEW);

        fixture.runAs(tenant, () -> {
            assertThat(documents.findAll(manageSpec))
                    .as("document.manage's own predicate is an unconditional match -- the handle, not the bytes")
                    .extracting(Document::getId).contains(docRef.get().getId());

            assertThat(documents.findAll(viewSpec))
                    .as("document.view's predicate, the one a content endpoint would gate on, still excludes it")
                    .extracting(Document::getId).doesNotContain(docRef.get().getId());
        });
    }

    private UUID newDocument(UUID tenant, Case c, UUID uploadedBy, UUID targetDepartmentId) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setTargetDepartmentId(targetDepartmentId);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documents.saveAndFlush(d).getId();
    }

    private UUID newTargetedDocument(UUID tenant, Case c, UUID uploadedBy, UUID targetDepartmentId) {
        return newDocument(tenant, c, uploadedBy, targetDepartmentId);
    }
}
