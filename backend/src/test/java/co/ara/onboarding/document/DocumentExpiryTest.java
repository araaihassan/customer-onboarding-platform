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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 28: {@code DocumentService.expiring} -- a read only, storing and
 * exposing an already-persisted {@code expires_at} (Task 15's own {@code
 * CreateDocumentRequest.expiresAt} field), never firing anything itself.
 * Sub-project 6 owns the actual notification (design spec's own scope table).
 *
 * Every case below goes through {@link DocumentService#expiring}, never the
 * repository directly, so the same {@code AuthorizedQuery}-mediated scope and
 * audience narrowing {@link DocumentServiceTest#listAndGetAreNarrowedToTheCallersOwnUploadsAtAssignedScope}
 * and its neighbours already prove for {@code list()}/{@code forCase()} is
 * proven here too, rather than re-derived.
 */
class DocumentExpiryTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired DocumentRepository documentRepository;
    @Autowired RoleService roles;

    /**
     * Both scope AND audience narrow {@code expiring()} exactly as they do
     * {@code list()}/{@code forCase()} -- proven in one test, at DEPARTMENT
     * scope, the narrowest scope {@code document.view} is catalogued at with
     * a real ownership column to test against (CLAUDE.md: "wherever a
     * permission is catalogued at several scopes, at least one [read] test
     * must run at the narrowest one"):
     * <ul>
     *   <li>a document on a case owned by the reader's OWN department,
     *       untargeted, expiring within the window -- visible;</li>
     *   <li>a document on a case owned by a DIFFERENT department -- excluded
     *       by SCOPE ({@code scoping.DocumentDescriptor.departmentScope});</li>
     *   <li>a document on the reader's OWN department's case, but TARGETED at
     *       a different department -- in scope, yet excluded by AUDIENCE
     *       ({@code scoping.DocumentAudienceFilter.internalAudience}), proving
     *       the two narrowings are independent and both apply.</li>
     * </ul>
     */
    @Test
    void expiringHonoursBothScopeAndAudienceAtTheNarrowestCatalogedScope() {
        UUID tenant = fixture.createTenant("doc-expiry-scope-" + Uuid7.generate());
        var reader = new UUID[1];
        var inScopeVisibleId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID ownDepartment = fixture.createDepartment(tenant, "Reader's Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Department");

            reader[0] = fixture.createUserInDepartment(
                    tenant, "expiry-dept-reader+" + Uuid7.generate() + "@example.com", ownDepartment);
            grant(reader[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.DEPARTMENT));

            UUID uploader = fixture.createUser(tenant, "expiry-uploader+" + Uuid7.generate() + "@example.com");

            Case ownCase = journey.newCase(tenant, null, ownDepartment, null);
            Case otherCase = journey.newCase(tenant, null, otherDepartment, null);

            Instant soon = Instant.now(clock).plus(Duration.ofDays(1));

            // Visible: in the reader's own department's scope, untargeted.
            inScopeVisibleId[0] = expiringDocument(tenant, ownCase, uploader, soon, null);

            // Excluded by SCOPE: a different department's case entirely.
            expiringDocument(tenant, otherCase, uploader, soon, null);

            // In scope (reader's own department's case), but excluded by
            // AUDIENCE: targeted at the OTHER department.
            expiringDocument(tenant, ownCase, uploader, soon, otherDepartment);
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenant, reader[0], () ->
                result.set(documents.expiring(Duration.ofDays(30), Pageable.unpaged()).getContent()));

        assertThat(result.get()).extracting(DocumentView::id).containsExactly(inScopeVisibleId[0]);
    }

    /**
     * Retirement (Task 18's own {@code retire}) must exclude a document from
     * this read exactly as it already does {@code list()}/{@code forCase()} --
     * {@link DocumentService} reuses the very same private {@code
     * notRetired()} helper, so a RETIRED document with an otherwise-matching
     * {@code expires_at} must never appear.
     */
    @Test
    void aRetiredDocumentNeverAppearsEvenWithAMatchingExpiryDate() {
        UUID tenant = fixture.createTenant("doc-expiry-retired-" + Uuid7.generate());
        var actor = new UUID[1];
        var activeId = new UUID[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            actor[0] = fixture.createUser(tenant, "expiry-retired+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            Instant soon = Instant.now(clock).plus(Duration.ofDays(1));
            activeId[0] = expiringDocument(tenant, c, actor[0], soon, null);

            UUID retiredId = expiringDocument(tenant, c, actor[0], soon, null);
            Document retired = documentRepository.findById(retiredId).orElseThrow();
            retired.setStatus(DocumentStatus.RETIRED);
            documentRepository.saveAndFlush(retired);
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                result.set(documents.expiring(Duration.ofDays(30), Pageable.unpaged()).getContent()));

        assertThat(result.get()).extracting(DocumentView::id).containsExactly(activeId[0]);
    }

    /**
     * A document whose {@code expires_at} is {@code null} (never set --
     * {@link CreateDocumentRequest#expiresAt} is nullable and most documents
     * never populate it) is never "expiring": the partial index itself
     * (design spec / {@code V23__document.sql}'s {@code
     * document_tenant_expiry_idx}) is defined {@code WHERE expires_at IS NOT
     * NULL}, so the query must filter it out explicitly, not merely rely on
     * a null failing a {@code <=} comparison to look correct by accident.
     */
    @Test
    void aDocumentWithNoExpiryDateNeverAppears() {
        UUID tenant = fixture.createTenant("doc-expiry-null-" + Uuid7.generate());
        var actor = new UUID[1];
        var expiringId = new UUID[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            actor[0] = fixture.createUser(tenant, "expiry-null+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            Instant soon = Instant.now(clock).plus(Duration.ofDays(1));
            expiringId[0] = expiringDocument(tenant, c, actor[0], soon, null);
            expiringDocument(tenant, c, actor[0], null, null); // never expires
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                result.set(documents.expiring(Duration.ofDays(30), Pageable.unpaged()).getContent()));

        assertThat(result.get()).extracting(DocumentView::id).containsExactly(expiringId[0]);
    }

    /**
     * A cross-tenant document is never visible regardless of how well its
     * {@code expires_at} matches the window -- RLS alone already guarantees
     * this (the connection is bound to one tenant at a time), but it is cheap
     * to confirm live given how central "a cross-tenant id is a 404, never a
     * 500 or a leak" is to this codebase's own invariants.
     */
    @Test
    void aCrossTenantDocumentNeverAppearsRegardlessOfItsExpiryDate() {
        UUID tenantA = fixture.createTenant("doc-expiry-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-expiry-tenant-b-" + Uuid7.generate());
        var actorB = new UUID[1];

        fixture.runAs(tenantA, () -> {
            Case c = journey.newCase(tenantA);
            UUID uploader = fixture.createUser(tenantA, "expiry-tenant-a+" + Uuid7.generate() + "@example.com");
            Instant soon = Instant.now(clock).plus(Duration.ofDays(1));
            expiringDocument(tenantA, c, uploader, soon, null);
        });

        fixture.runAs(tenantB, () -> {
            actorB[0] = fixture.createUser(tenantB, "expiry-tenant-b+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenantB, actorB[0], PermissionKeys.DOCUMENT_VIEW);
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenantB, actorB[0], () ->
                result.set(documents.expiring(Duration.ofDays(30), Pageable.unpaged()).getContent()));

        assertThat(result.get()).isEmpty();
    }

    /**
     * The boundary is INCLUSIVE at exactly {@code within} (the brief's own
     * example: 30 days out) -- {@code <=}, not {@code <}. A document expiring
     * one second past that same boundary is excluded. Both documents are
     * created against the SAME captured {@code now} so the two expiry
     * instants differ by exactly one second, and {@code expiring} is called
     * immediately afterward -- the real clock only ever advances between
     * capturing {@code now} and the call itself, which can only push the
     * query's own cutoff (computed from {@code Instant.now(clock)} at call
     * time) LATER than {@code now.plus(within)}, never earlier -- so the
     * exactly-at-boundary document is provably included by this ordering, and
     * the one-second-past document -- ordinary test execution taking far less
     * than a second -- is provably still excluded.
     */
    @Test
    void theBoundaryIsInclusiveAtExactlyWithinButExcludesOneSecondPastIt() {
        UUID tenant = fixture.createTenant("doc-expiry-boundary-" + Uuid7.generate());
        var actor = new UUID[1];
        var atBoundaryId = new UUID[1];
        var now = new Instant[1];

        fixture.runAs(tenant, () -> {
            Case c = journey.newCase(tenant);
            actor[0] = fixture.createUser(tenant, "expiry-boundary+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            now[0] = Instant.now(clock);
            Duration within = Duration.ofDays(30);
            atBoundaryId[0] = expiringDocument(tenant, c, actor[0], now[0].plus(within), null);
            expiringDocument(tenant, c, actor[0], now[0].plus(within).plusSeconds(1), null);
        });

        AtomicReference<List<DocumentView>> result = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                result.set(documents.expiring(Duration.ofDays(30), Pageable.unpaged()).getContent()));

        assertThat(result.get()).extracting(DocumentView::id).containsExactly(atBoundaryId[0]);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID expiringDocument(UUID tenant, Case c, UUID uploadedBy, Instant expiresAt, UUID targetDepartmentId) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Expiring Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setTargetDepartmentId(targetDepartmentId);
        d.setExpiresAt(expiresAt);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
