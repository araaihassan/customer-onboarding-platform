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
        var docRef = new java.util.concurrent.atomic.AtomicReference<Document>();
        fixture.runAs(tenant, () -> {
            UUID legal = fixture.createDepartment(tenant, "Legal");
            UUID other = fixture.createDepartment(tenant, "Other");
            userRef.set(fixture.createUserInDepartment(tenant, "reader@doc-aud-content.example", other));
            Case c = journey.newCase(tenant);
            UUID docId = newTargetedDocument(tenant, c, userRef.get(), legal);
            docRef.set(documents.findById(docId).orElseThrow());
        });

        AuthContext ctx = new AuthContext(tenant, userRef.get(), UserType.INTERNAL,
                null, Set.of());

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
