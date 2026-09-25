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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 32: {@link DocumentService#visibilitySummary} -- the `docs` screen's
 * "08 VISIBLE · 61 HIDDEN BY SCOPE" line (`SCREENS.md` §7), the codebase's
 * SECOND deliberate authorization bypass. See that method's own javadoc for
 * the full safety argument; this class proves the properties the brief
 * itself names -- the count is bounded to the caller's own filter context, a
 * narrower-scoped caller never sees FEWER hidden documents than a
 * broader-scoped one, {@code hidden} never renders negative even when {@code
 * visible} and {@code total} are contrived to be equal -- PLUS two isolation
 * properties added at review round 1, after a security review found the
 * first version of this method bypassed the AUDIENCE filter too, not just
 * the SCOPE union: a portal contact's counts must never include another
 * customer's documents in the SAME tenant ({@link
 * #aPortalContactsTotalNeverIncludesAnotherCustomersDocumentsInTheSameTenant}),
 * and RLS must still confine {@code total} to the current tenant now that
 * the scope union (but never tenant isolation) is bypassed ({@link
 * #aCrossTenantDocumentNeverContributesToEitherVisibleOrTotal}).
 */
class DocumentVisibilitySummaryTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentService documents;
    @Autowired DocumentRepository documentRepository;
    @Autowired RoleService roles;

    /**
     * An ALL-scoped actor with no audience narrowing in play (every document
     * COMPANY_SHARED and untargeted) sees every document their own {@link
     * DocumentService#list} would -- {@code visible} equals {@code total}
     * exactly, so {@code hidden} is precisely zero, never negative. This is
     * the "contrived to be close" boundary the brief's own Step 1 names:
     * {@code total - visible == 0} is the smallest gap the clamp could ever
     * need to guard, proven directly rather than only by code inspection of
     * {@code Math.max}.
     */
    @Test
    void hiddenIsExactlyZeroNeverNegativeWhenTheCallerAlreadySeesEverything() {
        UUID tenant = fixture.createTenant("doc-vis-summary-zero-" + Uuid7.generate());
        var actor = new UUID[1];

        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "vis-summary-all+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            Case c = journey.newCase(tenant);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
        });

        AtomicReference<DocumentVisibilitySummaryView> summary = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> summary.set(documents.visibilitySummary(null)));

        assertThat(summary.get().visible()).isEqualTo(3);
        assertThat(summary.get().hidden()).isZero();
    }

    /**
     * The property the brief's Step 1 names explicitly: a caller narrower in
     * scope than another must see a hidden count that is {@code >=} the
     * broader caller's, never less. A DEPARTMENT-scoped reader sees only
     * their own department's case's documents through the fully-authorized
     * {@code visible} half, while {@code hidden}'s RLS-only {@code total}
     * still counts every ACTIVE, COMPANY_SHARED document in the tenant
     * regardless of department -- so the department-scoped reader's hidden
     * count must be strictly greater than zero, and the ALL-scoped reader's
     * (who can already see everything) must be exactly zero.
     */
    @Test
    void aNarrowerScopedCallerSeesAHiddenCountAtLeastAsLargeAsABroaderScopedCaller() {
        UUID tenant = fixture.createTenant("doc-vis-summary-narrow-" + Uuid7.generate());
        var deptReader = new UUID[1];
        var allReader = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID ownDepartment = fixture.createDepartment(tenant, "Vis Summary Own " + Uuid7.generate());
            UUID otherDepartment = fixture.createDepartment(tenant, "Vis Summary Other " + Uuid7.generate());

            deptReader[0] = fixture.createUserInDepartment(
                    tenant, "vis-summary-dept+" + Uuid7.generate() + "@example.com", ownDepartment);
            grant(deptReader[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.DEPARTMENT));

            allReader[0] = fixture.createUser(tenant, "vis-summary-broad+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, allReader[0], PermissionKeys.DOCUMENT_VIEW);

            UUID uploader = fixture.createUser(tenant, "vis-summary-uploader+" + Uuid7.generate() + "@example.com");

            Case ownCase = journey.newCase(tenant, null, ownDepartment, null);
            Case otherCase = journey.newCase(tenant, null, otherDepartment, null);

            // Visible to deptReader (own department's case).
            document(tenant, ownCase, uploader, VisibilityTier.COMPANY_SHARED);
            // Outside deptReader's department scope -- contributes to `total`
            // (RLS-only) but not to deptReader's own `visible`.
            document(tenant, otherCase, uploader, VisibilityTier.COMPANY_SHARED);
            document(tenant, otherCase, uploader, VisibilityTier.COMPANY_SHARED);
        });

        AtomicReference<DocumentVisibilitySummaryView> deptSummary = new AtomicReference<>();
        fixture.runAsUser(tenant, deptReader[0], () -> deptSummary.set(documents.visibilitySummary(null)));

        AtomicReference<DocumentVisibilitySummaryView> allSummary = new AtomicReference<>();
        fixture.runAsUser(tenant, allReader[0], () -> allSummary.set(documents.visibilitySummary(null)));

        // The broader (ALL-scoped) reader already sees everything: nothing hidden.
        assertThat(allSummary.get().hidden()).isZero();

        // The narrower (DEPARTMENT-scoped) reader sees strictly fewer visible
        // documents and, in consequence, a hidden count >= the broader
        // reader's -- and here, strictly greater, since two documents sit
        // outside their department.
        assertThat(deptSummary.get().visible()).isEqualTo(1);
        assertThat(deptSummary.get().hidden()).isEqualTo(2);
        assertThat(deptSummary.get().hidden()).isGreaterThanOrEqualTo(allSummary.get().hidden());
    }

    /**
     * The count is bounded to the caller's own filter context (the brief's
     * own words) -- a {@code visibilityTier} filter narrows both halves
     * identically, not just {@code visible}. Two COMPANY_SHARED documents and
     * one SENSITIVE document exist; filtering by COMPANY_SHARED must count
     * only the two, never leak the SENSITIVE one into either number.
     */
    @Test
    void theSummaryIsBoundedToTheSameVisibilityTierFilterAsList() {
        UUID tenant = fixture.createTenant("doc-vis-summary-tier-" + Uuid7.generate());
        var actor = new UUID[1];

        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "vis-summary-tier+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            Case c = journey.newCase(tenant);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            document(tenant, c, actor[0], VisibilityTier.SENSITIVE);
        });

        AtomicReference<DocumentVisibilitySummaryView> filtered = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () ->
                filtered.set(documents.visibilitySummary(VisibilityTier.COMPANY_SHARED)));

        assertThat(filtered.get().visible()).isEqualTo(2);
        assertThat(filtered.get().hidden()).isZero();
    }

    /**
     * RETIRED excluded from {@code total} exactly as {@link
     * DocumentService#list}/{@link DocumentService#forCase} already exclude
     * it from {@code visible} -- {@code visibilitySummary} shares the same
     * {@code notRetired()} predicate on both halves ({@code
     * DocumentService#withTier}), so a retired document must not inflate
     * either number.
     */
    @Test
    void aRetiredDocumentIsExcludedFromBothVisibleAndTotal() {
        UUID tenant = fixture.createTenant("doc-vis-summary-retired-" + Uuid7.generate());
        var actor = new UUID[1];

        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "vis-summary-retired+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenant, actor[0], PermissionKeys.DOCUMENT_VIEW);

            Case c = journey.newCase(tenant);
            document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            UUID retiredId = document(tenant, c, actor[0], VisibilityTier.COMPANY_SHARED);
            Document retired = documentRepository.findById(retiredId).orElseThrow();
            retired.setStatus(DocumentStatus.RETIRED);
            documentRepository.saveAndFlush(retired);
        });

        AtomicReference<DocumentVisibilitySummaryView> summary = new AtomicReference<>();
        fixture.runAsUser(tenant, actor[0], () -> summary.set(documents.visibilitySummary(null)));

        assertThat(summary.get().visible()).isEqualTo(1);
        assertThat(summary.get().hidden()).isZero();
    }

    /**
     * CRITICAL (review round 1), fixed: the exact live scenario the security
     * review found and reproduced with a MockMvc probe against the operator
     * {@code GET /documents/visibility-summary} route, carrying a portal
     * contact's own JWT -- {@code {"visible":1,"hidden":3}} where the 3
     * "hidden" were another customer's documents in the SAME tenant, not
     * documents merely out of the portal contact's record-level scope. Root
     * cause: {@code authz.PortalPermissions} grants portal contacts {@code
     * document.view} at {@code Scope.ALL} (safe elsewhere ONLY because
     * {@code scoping.DocumentAudienceFilter} narrows every other read
     * reaching that grant), and the FIRST version of {@code
     * visibilitySummary}'s {@code total} bypassed the audience filter along
     * with the scope union, so nothing narrowed a portal actor to their own
     * customer on the bypass side.
     *
     * <p>Mirrors the reviewer's own probe at the service layer rather than
     * through MockMvc, matching this test class's own convention: seed
     * customers A and B in ONE tenant, a portal contact of A only, one
     * COMPANY_SHARED document at each customer's own case, call {@code
     * visibilitySummary} as A's portal contact, and confirm B's document
     * contributes to NEITHER {@code visible} NOR {@code hidden} -- before
     * the fix this asserted {@code visible=1, hidden=3} (this test's own
     * three B documents); after the fix, {@code hidden} is exactly zero,
     * since {@code DocumentAudienceFilter.portalAudience}'s {@code
     * atMyCustomer} conjunct now applies to {@code total} too, through
     * {@code AuthorizationPredicateBuilder#forPermissionIgnoringScope}.
     */
    @Test
    void aPortalContactsTotalNeverIncludesAnotherCustomersDocumentsInTheSameTenant() {
        UUID tenant = fixture.createTenant("doc-vis-summary-portal-" + Uuid7.generate());
        var portalUserId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID customerA = fixture.createCustomer(
                    tenant, "Vis Summary Portal A " + Uuid7.generate(), null, null, null);
            UUID customerB = fixture.createCustomer(
                    tenant, "Vis Summary Portal B " + Uuid7.generate(), null, null, null);

            portalUserId[0] = fixture.createPortalUserForContact(
                    tenant, customerA, "vis-summary-portal+" + Uuid7.generate() + "@example.com");

            UUID staffUploader = fixture.createUser(
                    tenant, "vis-summary-portal-staff+" + Uuid7.generate() + "@example.com");
            Case caseA = journey.newCaseForCustomer(tenant, customerA);
            Case caseB = journey.newCaseForCustomer(tenant, customerB);

            // Customer A's own document -- the portal contact's own customer;
            // must be counted (visible).
            document(tenant, caseA, staffUploader, VisibilityTier.COMPANY_SHARED);
            // Customer B's documents -- a DIFFERENT customer, same tenant.
            // Must contribute to NEITHER visible NOR hidden: this is the
            // exact disclosure review round 1 found live.
            document(tenant, caseB, staffUploader, VisibilityTier.COMPANY_SHARED);
            document(tenant, caseB, staffUploader, VisibilityTier.COMPANY_SHARED);
            document(tenant, caseB, staffUploader, VisibilityTier.COMPANY_SHARED);
        });

        AtomicReference<DocumentVisibilitySummaryView> summary = new AtomicReference<>();
        fixture.runAsUser(tenant, portalUserId[0], () -> summary.set(documents.visibilitySummary(null)));

        assertThat(summary.get().visible()).isEqualTo(1);
        assertThat(summary.get().hidden()).isZero();
    }

    /**
     * IMPORTANT 3 (review round 1): the one read in the codebase where RLS is
     * the SOLE remaining boundary on {@code total}, even after the Critical 1
     * fix above -- {@code DocumentAudienceFilter} narrows by CUSTOMER, never
     * by tenant, so cross-TENANT isolation on the scope-bypassed half rests
     * entirely on Postgres RLS ({@code app.tenant_id}) at the database layer.
     * Previously asserted only in this method's own javadoc prose, never
     * exercised -- matches every other module's own {@code *IsolationTest}
     * precedent of proving RLS live rather than trusting it.
     */
    @Test
    void aCrossTenantDocumentNeverContributesToEitherVisibleOrTotal() {
        UUID tenantA = fixture.createTenant("doc-vis-summary-tenant-a-" + Uuid7.generate());
        UUID tenantB = fixture.createTenant("doc-vis-summary-tenant-b-" + Uuid7.generate());
        var actorA = new UUID[1];

        fixture.runAs(tenantA, () -> {
            actorA[0] = fixture.createUser(tenantA, "vis-summary-tenant-a+" + Uuid7.generate() + "@example.com");
            fixture.grantAtAllScope(tenantA, actorA[0], PermissionKeys.DOCUMENT_VIEW);

            Case c = journey.newCase(tenantA);
            document(tenantA, c, actorA[0], VisibilityTier.COMPANY_SHARED);
        });

        fixture.runAs(tenantB, () -> {
            UUID uploaderB = fixture.createUser(tenantB, "vis-summary-tenant-b+" + Uuid7.generate() + "@example.com");
            Case cB = journey.newCase(tenantB);
            // Two documents in a DIFFERENT tenant -- must never contribute to
            // tenant A's actor's total, visible, or hidden, regardless of how
            // widely scoped that actor is.
            document(tenantB, cB, uploaderB, VisibilityTier.COMPANY_SHARED);
            document(tenantB, cB, uploaderB, VisibilityTier.COMPANY_SHARED);
        });

        AtomicReference<DocumentVisibilitySummaryView> summary = new AtomicReference<>();
        fixture.runAsUser(tenantA, actorA[0], () -> summary.set(documents.visibilitySummary(null)));

        assertThat(summary.get().visible()).isEqualTo(1);
        assertThat(summary.get().hidden()).isZero();
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID document(UUID tenant, Case c, UUID uploadedBy, VisibilityTier tier) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(c.getId());
        d.setCustomerId(c.getCustomerId());
        d.setName("Fixture Vis Summary Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(tier);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        return documentRepository.saveAndFlush(d).getId();
    }
}
