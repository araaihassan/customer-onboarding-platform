package co.ara.onboarding.agreement;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 14: {@link AgreementReviewService#review} -- the mandatory four-eyes review of a
 * submitted {@link AgreementVersion} (spec sections 4.5/5.3, invariant 7). {@code
 * AgreementSubmitTest} (Task 13) already proved {@code submit} freezes the version this
 * class reviews; {@code AgreementTestSupport}/{@code AgreementDraftTest}'s {@code grant}
 * idiom (hand-built role, {@code roles.createRole}/{@code roles.assignRole}) is reused
 * here verbatim for the narrowest-scope and manage-without-review cases.
 */
class AgreementReviewTest extends PostgresTestBase {

    private static final byte[] PDF_BYTES =
            "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired AgreementTestSupport support;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService reviewService;
    @Autowired AgreementRepository agreements;
    @Autowired AgreementVersionRepository versions;
    @Autowired AgreementVersionReviewRepository versionReviews;
    @Autowired AuditEventRepository auditEvents;
    @Autowired RoleService roles;

    /** Result of {@link #submitAsUsers}: the agreement's id, its lock version right after
     * submission, and the version that was just frozen. */
    private record Submitted(UUID agreementId, long lockVersion, AgreementVersionView version) {}

    @Test
    void theSubmitterCannotApproveTheirOwnVersion() {
        UUID tenant = fixture.createTenant("agr-review-self-submitter");
        UUID actor = fixture.createAdministrator(tenant, "actor+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, actor, actor);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor, () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion()))))
                .isInstanceOf(SelfReviewException.class);

        fixture.runAs(tenant, () -> {
            assertThat(agreements.findById(submitted.agreementId()).orElseThrow().getStatus())
                    .isEqualTo(AgreementStatus.UNDER_REVIEW);
            assertThat(versionReviews.ofVersions(List.of(submitted.version().id()))).isEmpty();
        });
    }

    @Test
    void theLastEditorCannotApproveEvenIfSomeoneElseSubmitted() {
        UUID tenant = fixture.createTenant("agr-review-self-editor");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, editorA, () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion()))))
                .isInstanceOf(SelfReviewException.class);

        fixture.runAs(tenant, () -> {
            assertThat(agreements.findById(submitted.agreementId()).orElseThrow().getStatus())
                    .isEqualTo(AgreementStatus.UNDER_REVIEW);
            assertThat(versionReviews.ofVersions(List.of(submitted.version().id()))).isEmpty();
        });
    }

    @Test
    void aThirdPersonApprovesAndTheAgreementIsApproved() {
        UUID tenant = fixture.createTenant("agr-review-third-party-approve");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        var resultRef = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewerC, () -> resultRef[0] = reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion())));

        assertThat(resultRef[0].agreement().status()).isEqualTo(AgreementStatus.APPROVED);
        fixture.runAs(tenant, () -> {
            AgreementVersionReview review = versionReviews.ofVersions(List.of(submitted.version().id())).get(0);
            assertThat(review.getDecision()).isEqualTo(ReviewDecision.APPROVE);
            assertThat(review.getReviewerId()).isEqualTo(reviewerC);
            assertThat(review.getReason()).isNull();
        });
    }

    @Test
    void rejectionNeedsAReasonAndReturnsToDraft() {
        UUID tenant = fixture.createTenant("agr-review-reject-needs-reason");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.REJECT, "  ", submitted.lockVersion()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a reason");

        fixture.runAs(tenant, () -> assertThat(agreements.findById(submitted.agreementId()).orElseThrow().getStatus())
                .isEqualTo(AgreementStatus.UNDER_REVIEW));

        var resultRef = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewerC, () -> resultRef[0] = reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Missing an initial", submitted.lockVersion())));

        assertThat(resultRef[0].agreement().status()).isEqualTo(AgreementStatus.DRAFT);
        fixture.runAs(tenant, () -> {
            AgreementVersionReview review = versionReviews.ofVersions(List.of(submitted.version().id())).get(0);
            assertThat(review.getDecision()).isEqualTo(ReviewDecision.REJECT);
            assertThat(review.getReason()).isEqualTo("Missing an initial");
        });
    }

    @Test
    void aRejectedVersionKeepsItsReviewAndTheNextSubmitIsANewVersion() {
        UUID tenant = fixture.createTenant("agr-review-reject-then-resubmit");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted firstSubmit = submitAsUsers(tenant, editorA, submitterB);

        var afterReject = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewerC, () -> afterReject[0] = reviewService.review(
                firstSubmit.agreementId(), firstSubmit.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Needs another look", firstSubmit.lockVersion())));

        var secondSubmit = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitterB, () -> secondSubmit[0] = agreementService.submit(
                firstSubmit.agreementId(), afterReject[0].agreement().lockVersion()));

        assertThat(secondSubmit[0].agreement().status()).isEqualTo(AgreementStatus.UNDER_REVIEW);
        assertThat(secondSubmit[0].versions()).hasSize(2);
        AgreementVersionView v1 = secondSubmit[0].versions().stream()
                .filter(v -> v.versionNumber() == 1).findFirst().orElseThrow();
        AgreementVersionView v2 = secondSubmit[0].versions().stream()
                .filter(v -> v.versionNumber() == 2).findFirst().orElseThrow();

        fixture.runAs(tenant, () -> {
            List<AgreementVersionReview> v1Reviews = versionReviews.ofVersions(List.of(v1.id()));
            assertThat(v1Reviews).hasSize(1);
            assertThat(v1Reviews.get(0).getDecision()).isEqualTo(ReviewDecision.REJECT);
            assertThat(v1Reviews.get(0).getReason()).isEqualTo("Needs another look");
            assertThat(versionReviews.ofVersions(List.of(v2.id()))).isEmpty();
        });
    }

    @Test
    void reviewingAnythingButTheLatestVersionIsRefused() {
        UUID tenant = fixture.createTenant("agr-review-only-latest");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted firstSubmit = submitAsUsers(tenant, editorA, submitterB);

        var afterReject = new AgreementDetailView[1];
        fixture.runAsUser(tenant, reviewerC, () -> afterReject[0] = reviewService.review(
                firstSubmit.agreementId(), firstSubmit.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Try again", firstSubmit.lockVersion())));

        var secondSubmit = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitterB, () -> secondSubmit[0] = agreementService.submit(
                firstSubmit.agreementId(), afterReject[0].agreement().lockVersion()));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                firstSubmit.agreementId(), 1,
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, secondSubmit[0].agreement().lockVersion()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only the latest version");
    }

    @Test
    void reviewIsRefusedOutsideUnderReview() {
        UUID tenant = fixture.createTenant("agr-review-status-guard");
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        assertThatThrownBy(() -> fixture.runAsReturning(tenant, () -> reviewService.review(
                agreementId[0], 1, new ReviewAgreementRequest(ReviewDecision.APPROVE, null, lockVersion[0]))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Review Focus 4: two decisions racing the same stale lockVersion is a conflict, not a duplicate row. */
    @Test
    void aSecondDecisionOnTheSameVersionIsAConflictNotADuplicate() {
        UUID tenant = fixture.createTenant("agr-review-conflict-not-duplicate");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion())));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion()))))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        fixture.runAs(tenant, () -> assertThat(versionReviews.ofVersions(List.of(submitted.version().id())))
                .hasSize(1));
    }

    @Test
    void theDatabaseRefusesASecondReviewRowForOneVersion() {
        UUID tenant = fixture.createTenant("agr-review-unique-index");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        fixture.runAs(tenant, () -> versionReviews.saveAndFlush(new AgreementVersionReview(
                Uuid7.generate(), tenant, submitted.version().id(), ReviewDecision.APPROVE, reviewerC,
                Instant.now(), null)));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> versionReviews.saveAndFlush(new AgreementVersionReview(
                Uuid7.generate(), tenant, submitted.version().id(), ReviewDecision.REJECT, reviewerC,
                Instant.now(), "duplicate"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Narrowest scope (CLAUDE.md): a TEAM-scoped agreement.review holder on their team's own case. */
    @Test
    void aTeamScopedReviewerAtTheNarrowestScopeCanReview() {
        UUID tenant = fixture.createTenant("agr-review-team-scope");
        var teamActor = new UUID[1];
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            UUID teamId = fixture.createTeam(tenant, "Delivery Team " + Uuid7.generate());
            teamActor[0] = fixture.createUser(tenant, "team-scoped+" + Uuid7.generate() + "@example.com");
            fixture.addToTeam(tenant, teamActor[0], teamId);
            grant(teamActor[0], Map.of(
                    PermissionKeys.AGREEMENT_REVIEW, Scope.TEAM,
                    PermissionKeys.AGREEMENT_VIEW, Scope.TEAM,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            Case c = journey.newCase(tenant, null, null, teamId);
            Milestone m = journey.newMilestone(tenant, c);
            Requirement r = journey.newRequirement(tenant, c, m);
            Agreement a = support.draftAgreementRowFor(tenant, c.getId(), r.getId());
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });

        Submitted submitted = submitExistingDraft(tenant, agreementId[0], lockVersion[0]);

        var resultRef = new AgreementDetailView[1];
        fixture.runAsUser(tenant, teamActor[0], () -> resultRef[0] = reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion())));

        assertThat(resultRef[0].agreement().status()).isEqualTo(AgreementStatus.APPROVED);
    }

    @Test
    void aHolderOfManageButNotReviewIsRefused() {
        UUID tenant = fixture.createTenant("agr-review-manage-not-review");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        Submitted submitted = submitAsUsers(tenant, editorA, submitterB);

        var manageOnly = new UUID[1];
        fixture.runAs(tenant, () -> {
            manageOnly[0] = fixture.createUser(tenant, "manage-only+" + Uuid7.generate() + "@example.com");
            grant(manageOnly[0], Map.of(
                    PermissionKeys.AGREEMENT_MANAGE, Scope.ALL,
                    PermissionKeys.AGREEMENT_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manageOnly[0], () -> reviewService.review(
                submitted.agreementId(), submitted.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, submitted.lockVersion()))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void approveAndRejectAreEachAudited() {
        UUID tenant = fixture.createTenant("agr-review-audited");
        UUID editorA = fixture.createAdministrator(tenant, "editor-a+" + Uuid7.generate() + "@example.com");
        UUID submitterB = fixture.createAdministrator(tenant, "submitter-b+" + Uuid7.generate() + "@example.com");
        UUID reviewerC = fixture.createAdministrator(tenant, "reviewer-c+" + Uuid7.generate() + "@example.com");

        Submitted approved = submitAsUsers(tenant, editorA, submitterB);
        var approvedCaseId = new UUID[1];
        fixture.runAs(tenant, () -> approvedCaseId[0] =
                agreements.findById(approved.agreementId()).orElseThrow().getCaseId());
        fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                approved.agreementId(), approved.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.APPROVE, null, approved.lockVersion())));

        Submitted rejected = submitAsUsers(tenant, editorA, submitterB);
        var rejectedCaseId = new UUID[1];
        fixture.runAs(tenant, () -> rejectedCaseId[0] =
                agreements.findById(rejected.agreementId()).orElseThrow().getCaseId());
        fixture.runAsUser(tenant, reviewerC, () -> reviewService.review(
                rejected.agreementId(), rejected.version().versionNumber(),
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Not this one", rejected.lockVersion())));

        fixture.runAs(tenant, () -> {
            List<AuditEvent> approvedEvents = auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_APPROVED.key().equals(e.getAction()))
                    .toList();
            assertThat(approvedEvents).hasSize(1);
            AuditEvent approvedEvent = approvedEvents.get(0);
            assertThat(approvedEvent.getResourceType()).isEqualTo("onboarding_case");
            assertThat(approvedEvent.getResourceId()).isEqualTo(approvedCaseId[0]);
            assertThat(approvedEvent.isTimelineVisible()).isTrue();
            assertThat(approvedEvent.getPayload()).contains(approved.agreementId().toString());

            List<AuditEvent> rejectedEvents = auditEvents.findAll().stream()
                    .filter(e -> AuditActions.AGREEMENT_REJECTED.key().equals(e.getAction()))
                    .toList();
            assertThat(rejectedEvents).hasSize(1);
            AuditEvent rejectedEvent = rejectedEvents.get(0);
            assertThat(rejectedEvent.getResourceType()).isEqualTo("onboarding_case");
            assertThat(rejectedEvent.getResourceId()).isEqualTo(rejectedCaseId[0]);
            assertThat(rejectedEvent.isTimelineVisible()).isTrue();
            assertThat(rejectedEvent.getPayload()).contains(rejected.agreementId().toString());
        });
    }

    /**
     * Builds a fresh draft agreement, then runs signatory/upload/submit as {@code editor}
     * (whose id ends up as the frozen version's {@code lastEditedBy}), with the final
     * {@code submit} call itself run as {@code submitter} -- the {@code
     * AgreementSubmitTest#theVersionRecordsSubmitterAndLastEditorSeparately} shape, reused
     * here so every review test can name its submitter/editor/reviewer independently.
     */
    private Submitted submitAsUsers(UUID tenant, UUID editor, UUID submitter) {
        var agreementId = new UUID[1];
        var lockVersion = new long[1];
        fixture.runAs(tenant, () -> {
            Agreement a = support.draftAgreementRow(tenant);
            agreementId[0] = a.getId();
            lockVersion[0] = a.getLockVersion();
        });
        return submitAsUsers(tenant, agreementId[0], lockVersion[0], editor, submitter);
    }

    private Submitted submitAsUsers(UUID tenant, UUID agreementId, long lockVersion, UUID editor, UUID submitter) {
        var signatoryUser = new UUID[1];
        fixture.runAs(tenant, () -> signatoryUser[0] =
                fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com"));

        var afterSig = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterSig[0] = agreementService.replaceSignatories(agreementId,
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, signatoryUser[0], "Approver")),
                        lockVersion)));

        var afterUpload = new AgreementDetailView[1];
        fixture.runAsUser(tenant, editor, () -> afterUpload[0] = agreementService.uploadDraftFile(agreementId,
                afterSig[0].agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));

        var result = new AgreementDetailView[1];
        fixture.runAsUser(tenant, submitter, () -> result[0] = agreementService.submit(
                agreementId, afterUpload[0].agreement().lockVersion()));

        return new Submitted(agreementId, result[0].agreement().lockVersion(), result[0].versions().get(0));
    }

    /** Same shape as {@link #submitAsUsers}, but as the fixture's own administrator throughout --
     * for the team-scoped test, where only the reviewer's identity matters. */
    private Submitted submitExistingDraft(UUID tenant, UUID agreementId, long lockVersion) {
        var signatoryUser = new UUID[1];
        fixture.runAs(tenant, () -> signatoryUser[0] =
                fixture.createUser(tenant, "signatory+" + Uuid7.generate() + "@example.com"));

        var afterSig = fixture.runAsReturning(tenant, () -> agreementService.replaceSignatories(agreementId,
                new ReplaceSignatoriesRequest(
                        List.of(new SignatoryRequest(SignatoryKind.INTERNAL, null, signatoryUser[0], "Approver")),
                        lockVersion)));
        var afterUpload = fixture.runAsReturning(tenant, () -> agreementService.uploadDraftFile(agreementId,
                afterSig.agreement().lockVersion(), new ByteArrayInputStream(PDF_BYTES), PDF_BYTES.length));
        var result = fixture.runAsReturning(tenant, () ->
                agreementService.submit(agreementId, afterUpload.agreement().lockVersion()));

        return new Submitted(agreementId, result.agreement().lockVersion(), result.versions().get(0));
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }
}
