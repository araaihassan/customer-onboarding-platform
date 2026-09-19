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
 * SECOND deliberate {@code AuthorizedQuery} bypass. See that method's own
 * javadoc for the full safety argument; this class proves the three
 * properties the brief itself names: the count is bounded to the caller's own
 * filter context, a narrower-scoped caller never sees FEWER hidden documents
 * than a broader-scoped one, and {@code hidden} never renders negative even
 * when {@code visible} and {@code total} are contrived to be equal (the
 * boundary the {@code Math.max(0, ...)} clamp exists for).
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
