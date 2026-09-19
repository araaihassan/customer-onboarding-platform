package co.ara.onboarding.document;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 27 (design spec 5.4): {@link DocumentReviewService#review} and the
 * cross-case pending-review queue. Follows {@code DocumentRequestServiceTest}'s
 * and {@code DocumentServiceTest.retiringReopensARequirementItSatisfied}'s own
 * fixture shapes exactly -- {@code publishedThreeStageWorkflow()} rather than
 * {@code publishedTemplate()} wherever a requirement is satisfied/reopened, so
 * the whole case does not complete out from under the assertion (the same
 * reasoning that test's own javadoc gives).
 */
class DocumentReviewServiceTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired DocumentReviewService review;
    @Autowired DocumentService documentService;
    @Autowired DocumentRepository documentRepository;
    @Autowired DocumentVersionRepository versionRepository;
    @Autowired DocumentRequestRepository requestRepository;
    @Autowired RequirementRepository requirementRepository;
    @Autowired RequirementService requirements;
    @Autowired RoleService roles;
    @Autowired CaseService cases;

    @Test
    void approvingSatisfiesARequiresReviewRequirement() {
        UUID tenant = fixture.createTenant("doc-review-approve-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-approve+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Review Approve Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            documentId[0] = createDocumentWithVersion(tenant, caseId[0], customerId, manager[0]);
            createFulfilledRequestRow(tenant, caseId[0], requirementId[0], manager[0], documentId[0], true);
        });

        var view = new DocumentVersionView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                view[0] = review.review(documentId[0], 1, ReviewDecision.APPROVED, "Looks good"));

        assertThat(view[0].reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(view[0].reviewedBy()).isEqualTo(manager[0]);
        assertThat(view[0].reviewNote()).isEqualTo("Looks good");
        assertThat(view[0].reviewedAt()).isNotNull();

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.SATISFIED);
            assertThat(r.getSatisfiedRef()).isEqualTo(documentId[0]);
            assertThat(r.getSatisfiedRefType()).isEqualTo(DocumentService.SATISFIED_REF_TYPE);
        });
    }

    @Test
    void rejectingAVersionThatAlreadySatisfiedReopensTheRequirement() {
        UUID tenant = fixture.createTenant("doc-review-reject-" + Uuid7.generate());
        var manager = new UUID[1];
        var caseId = new UUID[1];
        var requirementId = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-reject+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(tenant, "Review Reject Co " + Uuid7.generate(), null, null, null);
            UUID versionId = journey.publishedThreeStageWorkflow();
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            requirementId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).requirements().get(0).id();

            documentId[0] = createDocumentWithVersion(tenant, caseId[0], customerId, manager[0]);
            requirements.satisfy(requirementId[0], documentId[0], DocumentService.SATISFIED_REF_TYPE);
        });

        var view = new DocumentVersionView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                view[0] = review.review(documentId[0], 1, ReviewDecision.REJECTED, "Wrong file uploaded"));

        assertThat(view[0].reviewStatus()).isEqualTo(ReviewStatus.REJECTED);

        fixture.runAs(tenant, () -> {
            Requirement r = requirementRepository.findById(requirementId[0]).orElseThrow();
            assertThat(r.getStatus()).isEqualTo(RequirementStatus.OPEN);
            assertThat(r.getSatisfiedRef()).isNull();
            assertThat(r.getSatisfiedRefType()).isNull();
        });
    }

    /**
     * Already true by construction -- both {@code DocumentService.upload} and
     * {@code .addVersion} unconditionally start a new version PENDING -- so
     * this proves the behaviour rather than exercising anything {@link
     * DocumentReviewService} itself implements.
     */
    @Test
    void aNewVersionResetsReviewStatusToPending() {
        UUID tenant = fixture.createTenant("doc-review-new-version-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-newver+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                    PermissionKeys.DOCUMENT_UPLOAD, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () ->
                review.review(documentId[0], 1, ReviewDecision.APPROVED, null));

        // The real production path -- DocumentService.addVersion -- rather than
        // a hand-inserted row, so this actually proves the already-built
        // behaviour the brief names ("both upload and addVersion already
        // initialize every new version's reviewStatus to PENDING
        // unconditionally") rather than merely restating it.
        fixture.runAsUser(tenant, manager[0], () -> documentService.addVersion(documentId[0],
                new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length, "application/pdf"));

        fixture.runAs(tenant, () -> {
            DocumentVersion v2 = versionRepository.versionAt(documentId[0], 2).orElseThrow();
            assertThat(v2.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
        });
    }

    /**
     * Approving version 1 leaves version 2's own independent review outcome
     * completely untouched -- {@code DocumentService.addVersion}'s own javadoc
     * ("approving v1 says nothing about v2") -- each version's review outcome
     * is its own row, never shared or rolled forward.
     */
    @Test
    void approvingV1SaysNothingAboutV2() {
        UUID tenant = fixture.createTenant("doc-review-v1-v2-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-v1v2+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), manager[0]);
            addVersionRow(documentId[0], 2, ReviewStatus.PENDING, manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () ->
                review.review(documentId[0], 1, ReviewDecision.APPROVED, "v1 fine"));

        fixture.runAs(tenant, () -> {
            DocumentVersion v1 = versionRepository.versionAt(documentId[0], 1).orElseThrow();
            DocumentVersion v2 = versionRepository.versionAt(documentId[0], 2).orElseThrow();
            assertThat(v1.getReviewStatus()).isEqualTo(ReviewStatus.APPROVED);
            assertThat(v2.getReviewStatus()).isEqualTo(ReviewStatus.PENDING);
            assertThat(v2.getReviewedBy()).isNull();
        });
    }

    @Test
    void aHolderOfDocumentViewButNotDocumentReviewIsRefused() {
        UUID tenant = fixture.createTenant("doc-review-insufficient-" + Uuid7.generate());
        var viewer = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            viewer[0] = fixture.createUser(tenant, "review-insufficient+" + Uuid7.generate() + "@example.com");
            grant(viewer[0], Map.of(PermissionKeys.DOCUMENT_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), viewer[0]);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, viewer[0], () ->
                review.review(documentId[0], 1, ReviewDecision.APPROVED, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * CLAUDE.md's own convention: "wherever a permission is catalogued at
     * several scopes, at least one write test must run at the narrowest one."
     * {@code document.review} is catalogued ALL/DEPARTMENT/TEAM
     * ({@code PermissionCatalog.ORG_SCOPES}), so TEAM is the narrowest. The
     * case carries no current stage, so {@code StageWriteScopeGuard} has
     * nothing to narrow against and this proves scope resolution alone -- the
     * identical construction {@code DocumentRequestServiceTest
     * .aTeamScopedDocumentRequestHolderCanRequestOnACaseOwnedByTheirOwnTeam} uses.
     */
    @Test
    void aTeamScopedDocumentReviewHolderCanReviewAVersionOnACaseOwnedByTheirOwnTeam() {
        UUID tenant = fixture.createTenant("doc-review-team-" + Uuid7.generate());
        var teamScopedActor = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Fixture Team " + Uuid7.generate());
            teamScopedActor[0] = fixture.createUser(tenant, "review-team+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamScopedActor[0], team);
            grant(teamScopedActor[0], Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.TEAM));

            Case c = journey.newCase(tenant, null, null, team);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), teamScopedActor[0]);
        });

        var view = new DocumentVersionView[1];
        fixture.runAsUser(tenant, teamScopedActor[0], () ->
                view[0] = review.review(documentId[0], 1, ReviewDecision.APPROVED, "Team-scoped approval"));

        assertThat(view[0].reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
    }

    @Test
    void aCrossTenantDocumentIdAnswers404NeverForbidden() {
        UUID tenantA = fixture.createTenant("doc-review-xt-a-" + Uuid7.generate());
        var documentId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            UUID managerA = fixture.createUser(tenantA, "review-xt-a+" + Uuid7.generate() + "@example.com");
            grant(managerA, Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.ALL));
            Case c = journey.newCase(tenantA);
            documentId[0] = createDocumentWithVersion(tenantA, c.getId(), c.getCustomerId(), managerA);
        });

        UUID tenantB = fixture.createTenant("doc-review-xt-b-" + Uuid7.generate());
        var managerB = new UUID[1];
        fixture.runAs(tenantB, () -> {
            managerB[0] = fixture.createUser(tenantB, "review-xt-b+" + Uuid7.generate() + "@example.com");
            grant(managerB[0], Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenantB, managerB[0], () ->
                review.review(documentId[0], 1, ReviewDecision.APPROVED, null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aNonexistentVersionNumberAnswers404() {
        UUID tenant = fixture.createTenant("doc-review-noversion-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-noversion+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.ALL));
            Case c = journey.newCase(tenant);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), manager[0]);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager[0], () ->
                review.review(documentId[0], 99, ReviewDecision.APPROVED, null)))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Re-reviewing an already-approved version is treated as idempotent
     * re-application (this class's own javadoc states the reasoning) rather
     * than refused -- the latest decision simply overwrites the review
     * fields, and a second identical decision leaves the outcome unchanged.
     */
    @Test
    void approvingAnAlreadyApprovedVersionIsIdempotent() {
        UUID tenant = fixture.createTenant("doc-review-idempotent-" + Uuid7.generate());
        var manager = new UUID[1];
        var documentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            manager[0] = fixture.createUser(tenant, "review-idempotent+" + Uuid7.generate() + "@example.com");
            grant(manager[0], Map.of(
                    PermissionKeys.DOCUMENT_REVIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            Case c = journey.newCase(tenant);
            documentId[0] = createDocumentWithVersion(tenant, c.getId(), c.getCustomerId(), manager[0]);
        });

        fixture.runAsUser(tenant, manager[0], () ->
                review.review(documentId[0], 1, ReviewDecision.APPROVED, "First pass"));

        var second = new DocumentVersionView[1];
        fixture.runAsUser(tenant, manager[0], () ->
                second[0] = review.review(documentId[0], 1, ReviewDecision.APPROVED, "Second pass"));

        assertThat(second[0].reviewStatus()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(second[0].reviewNote()).isEqualTo("Second pass");
    }

    /**
     * Design spec 5.4's own words, quoted in the plan's brief: "an ordinary
     * AuthorizedQuery listing filtered to review_status = PENDING; it needs
     * no carve-out." A DEPARTMENT-scoped holder sees only the PENDING version
     * on their own department's case, never the other department's.
     */
    @Test
    void theCrossCasePendingQueueIsScopeFilteredWithNoCarveOut() {
        UUID tenant = fixture.createTenant("doc-review-pending-scope-" + Uuid7.generate());
        var deptActor = new UUID[1];
        var visibleDocumentId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "Dept A " + Uuid7.generate());
            UUID deptB = fixture.createDepartment(tenant, "Dept B " + Uuid7.generate());

            deptActor[0] = fixture.createUserInDepartment(tenant, "review-pending+" + Uuid7.generate() + "@example.com", deptA);
            grant(deptActor[0], Map.of(PermissionKeys.DOCUMENT_REVIEW, Scope.DEPARTMENT));

            Case caseA = journey.newCase(tenant, null, deptA, null);
            visibleDocumentId[0] = createDocumentWithVersion(tenant, caseA.getId(), caseA.getCustomerId(), deptActor[0]);

            Case caseB = journey.newCase(tenant, null, deptB, null);
            createDocumentWithVersion(tenant, caseB.getId(), caseB.getCustomerId(), deptActor[0]);
        });

        var page = new java.util.concurrent.atomic.AtomicReference<org.springframework.data.domain.Page<DocumentVersionView>>();
        fixture.runAsUser(tenant, deptActor[0], () -> page.set(review.pending(PageRequest.of(0, 20))));

        assertThat(page.get().getContent()).extracting(DocumentVersionView::documentId)
                .containsExactly(visibleDocumentId[0]);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID createDocumentWithVersion(UUID tenant, UUID caseId, UUID customerId, UUID uploadedBy) {
        Document d = new Document();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setCaseId(caseId);
        d.setCustomerId(customerId);
        d.setName("Fixture Document " + Uuid7.generate());
        d.setCategory(DocumentCategory.OTHER);
        d.setVisibilityTier(VisibilityTier.COMPANY_SHARED);
        d.setStatus(DocumentStatus.ACTIVE);
        d.setUploadedBy(uploadedBy);
        d = documentRepository.saveAndFlush(d);

        DocumentVersion v = new DocumentVersion(Uuid7.generate(), tenant, d.getId(), 1,
                "fixture-storage-key", 10L, "application/pdf", "0".repeat(64),
                ReviewStatus.PENDING, uploadedBy, Instant.now());
        versionRepository.saveAndFlush(v);

        d.setCurrentVersionId(v.getId());
        documentRepository.saveAndFlush(d);
        return d.getId();
    }

    private void addVersionRow(UUID documentId, int versionNo, ReviewStatus reviewStatus, UUID uploadedBy) {
        DocumentVersion v = new DocumentVersion(Uuid7.generate(),
                documentRepository.findById(documentId).orElseThrow().getTenantId(), documentId, versionNo,
                "fixture-storage-key-" + versionNo, 10L, "application/pdf", "0".repeat(64),
                reviewStatus, uploadedBy, Instant.now());
        versionRepository.saveAndFlush(v);
    }

    private UUID createFulfilledRequestRow(UUID tenant, UUID caseId, UUID requirementId, UUID requestedBy,
                                           UUID fulfilledDocumentId, boolean requiresReview) {
        DocumentRequest dr = new DocumentRequest();
        dr.setId(Uuid7.generate());
        dr.setTenantId(tenant);
        dr.setCaseId(caseId);
        dr.setRequirementId(requirementId);
        dr.setCategory(DocumentCategory.OTHER);
        dr.setRequiresReview(requiresReview);
        dr.setStatus(DocumentRequestStatus.FULFILLED);
        dr.setFulfilledDocumentId(fulfilledDocumentId);
        dr.setRequestedBy(requestedBy);
        dr.setRequestedAt(Instant.now());
        return requestRepository.saveAndFlush(dr).getId();
    }
}
