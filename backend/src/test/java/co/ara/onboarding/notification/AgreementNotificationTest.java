package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.AgreementRepository;
import co.ara.onboarding.agreement.AgreementReviewService;
import co.ara.onboarding.agreement.AgreementService;
import co.ara.onboarding.agreement.AgreementSignatureService;
import co.ara.onboarding.agreement.AgreementStatus;
import co.ara.onboarding.agreement.AgreementTestSupport;
import co.ara.onboarding.agreement.CancelAgreementRequest;
import co.ara.onboarding.agreement.PatchAgreementRequest;
import co.ara.onboarding.agreement.RecordSignatureRequest;
import co.ara.onboarding.agreement.ReviewAgreementRequest;
import co.ara.onboarding.agreement.ReviewDecision;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AgreementRecordMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGREEMENT_STATUS (spec 5.2, plan amendment 1): submit, approve/reject, send, the LAST
 * signature and cancel each notify the agreement's owner and the case owner, gated
 * agreement.view on the agreement itself. A partial signature is not announced (spec 1.2.7).
 */
class AgreementNotificationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired AgreementTestSupport agreementSupport;
    @Autowired AgreementRepository agreementRepository;
    @Autowired AgreementService agreementService;
    @Autowired AgreementReviewService agreementReviewService;
    @Autowired AgreementSignatureService agreementSignatureService;
    @Autowired CaseRepository caseRepository;
    @Autowired JourneyFixtures journey;
    @Autowired Clock clock;

    private record Arranged(UUID tenant, UUID caseId, UUID agreementId, UUID watcher) {}

    /**
     * One STRUCTURED_ONLY SIGNATURE case whose instantiated (DRAFT) agreement and whose case are
     * both owned by {@code watcher}, set through owner SQL; {@code watcher} holds {@code grants}.
     */
    private Arranged arrange(String slug, Map<String, Scope> grants) {
        UUID t = fixture.createTenant(slug);
        UUID watcher = fixture.runAsReturning(t, () -> fixture.createUser(t, "watcher@" + slug + ".test"));
        if (!grants.isEmpty()) support.grant(t, watcher, grants);
        UUID caseId = fixture.runAsReturning(t, () ->
                agreementSupport.openCaseWithSignatureRequirement(t, AgreementRecordMode.STRUCTURED_ONLY));
        UUID agreementId = fixture.runAsReturning(t, () -> agreementRepository.findByCaseId(caseId).get(0).getId());
        setOwners(agreementId, caseId, watcher);
        return new Arranged(t, caseId, agreementId, watcher);
    }

    private static Map<String, Scope> watcherGrants() {
        return Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL);
    }

    private void setOwners(UUID agreementId, UUID caseId, UUID owner) {
        ownerJdbc().update("update agreement set owner_user_id = ? where id = ?", owner, agreementId);
        ownerJdbc().update("update onboarding_case set owner_user_id = ? where id = ?", owner, caseId);
    }

    private UUID admin(UUID t, String who) {
        return fixture.createAdministrator(t, who + "+" + Uuid7.generate() + "@example.com");
    }

    private List<Map<String, Object>> statusRows(UUID t, UUID recipient) {
        return support.rowsFor(t, recipient).stream()
                .filter(r -> NotificationType.AGREEMENT_STATUS.name().equals(r.get("type"))).toList();
    }

    private static List<String> titles(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (String) r.get("title")).toList();
    }

    private void recordSignature(UUID t, AgreementTestSupport.Driven d, UUID signatoryId, long lockVersion) {
        UUID recorder = admin(t, "recorder");
        fixture.runAsUser(t, recorder, () -> agreementSignatureService.record(d.agreementId(),
                new RecordSignatureRequest(signatoryId, LocalDate.now(clock), "Wet ink, scanned", lockVersion),
                null, 0));
    }

    private long lockVersionOf(UUID t, UUID agreementId) {
        return fixture.runAsReturning(t, () -> agreementRepository.findById(agreementId).orElseThrow().getLockVersion());
    }

    // ---- the transitions -----------------------------------------------------------------

    @Test
    void eachTransitionNotifiesTheOwnerAndCaseOwner() {
        var a = arrange("agr-notify-each", watcherGrants());
        // Renamed in DRAFT: the notification must carry the agreement's current name, not the template's.
        UUID renamer = admin(a.tenant(), "renamer");
        fixture.runAsUser(a.tenant(), renamer, () -> agreementService.patch(a.agreementId(),
                new PatchAgreementRequest("Master Services Agreement", null, null, null, null, null,
                        lockVersionOf(a.tenant(), a.agreementId()))));

        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 1, null, null);
        recordSignature(a.tenant(), d, d.signatoryIds().get(0), d.lockVersion());

        var rows = statusRows(a.tenant(), a.watcher());
        assertThat(titles(rows)).containsExactly(
                "Master Services Agreement: Submitted for review",
                "Master Services Agreement: Approved",
                "Master Services Agreement: Sent for signature",
                "Master Services Agreement: Signed");
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("subject_type")).isEqualTo("agreement");
            assertThat(r.get("subject_id")).isEqualTo(a.agreementId());
            assertThat(r.get("case_id")).isEqualTo(a.caseId());
            assertThat((String) r.get("body")).contains("Fixture Case").contains("(Acme ");
        });
        assertThat(rows.stream().map(r -> r.get("tone")).toList())
                .containsExactly("INFO", "OK", "INFO", "OK");
    }

    @Test
    void aRejectionIsRiskToned() {
        var a = arrange("agr-notify-reject", watcherGrants());
        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.UNDER_REVIEW, 1, null, null);
        UUID reviewer = admin(a.tenant(), "reviewer");

        fixture.runAsUser(a.tenant(), reviewer, () -> agreementReviewService.review(d.agreementId(), 1,
                new ReviewAgreementRequest(ReviewDecision.REJECT, "Wrong governing law", d.lockVersion())));

        var rows = statusRows(a.tenant(), a.watcher());
        assertThat(rows).hasSize(2);   // submitted, then rejected
        var rejected = rows.get(1);
        assertThat((String) rejected.get("title")).endsWith("Rejected");
        assertThat(rejected.get("tone")).isEqualTo("RISK");
        assertThat(statusRows(a.tenant(), reviewer)).isEmpty();   // the actor hears nothing
    }

    @Test
    void aCancellationNotifies() {
        var a = arrange("agr-notify-cancel", watcherGrants());
        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 1, null, null);
        UUID canceller = admin(a.tenant(), "canceller");

        fixture.runAsUser(a.tenant(), canceller, () -> agreementService.cancel(d.agreementId(),
                new CancelAgreementRequest("Customer changed entity", d.lockVersion())));

        var rows = statusRows(a.tenant(), a.watcher());
        var last = rows.get(rows.size() - 1);
        assertThat((String) last.get("title")).isEqualTo("Fixture Agreement: Cancelled");
        assertThat(last.get("tone")).isEqualTo("RISK");
        assertThat(last.get("subject_id")).isEqualTo(d.agreementId());   // the cancelled row, not its successor
        assertThat(statusRows(a.tenant(), canceller)).isEmpty();
    }

    @Test
    void aPartialSignatureIsNotAnnounced() {
        var a = arrange("agr-notify-partial", watcherGrants());
        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 2, null, null);

        recordSignature(a.tenant(), d, d.signatoryIds().get(0), d.lockVersion());

        assertThat(titles(statusRows(a.tenant(), a.watcher())))
                .hasSize(3)
                .noneMatch(title -> title.endsWith("Signed"));
    }

    // ---- who may hear (agreement.view on the agreement itself) ---------------------------

    @Test
    void anOwnerWithoutAgreementViewHearsNothingAndNoNameLeaks() {
        // case.view alone: the case owner may see the case but not its agreements.
        var a = arrange("agr-notify-noview", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));

        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 1, null, null);
        recordSignature(a.tenant(), d, d.signatoryIds().get(0), d.lockVersion());

        assertThat(statusRows(a.tenant(), a.watcher())).isEmpty();
        assertThat(support.rowsFor(a.tenant(), a.watcher()))
                .noneMatch(r -> String.valueOf(r.get("title")).contains("Fixture Agreement")
                        || String.valueOf(r.get("body")).contains("Fixture Agreement"));
    }

    @Test
    void anOwnerWhoseAgreementViewIsOutOfScopeHearsNothing() {
        // TEAM scope, but the watcher is on no team: the agreement is out of their scope.
        var a = arrange("agr-notify-team", Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.TEAM,
                PermissionKeys.CASE_VIEW, Scope.ALL));

        agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 1, null, null);

        assertThat(statusRows(a.tenant(), a.watcher())).isEmpty();
    }

    @Test
    void anOwnerHoldingAgreementViewOnlyAtAssignedScopeHearsThroughTheirParticipation() {
        var grants = Map.of(PermissionKeys.AGREEMENT_VIEW, Scope.ASSIGNED, PermissionKeys.CASE_VIEW, Scope.ASSIGNED);
        var participating = arrange("agr-notify-assigned", grants);
        fixture.runAs(participating.tenant(), () -> journey.addParticipant(participating.tenant(),
                participating.caseId(), participating.watcher(), RelationshipType.PARTICIPANT, ParticipantStatus.ACTIVE));
        var bystander = arrange("agr-notify-assigned-no", grants);   // same grants, no participation

        agreementSupport.drive(participating.tenant(), participating.caseId(), AgreementStatus.UNDER_REVIEW, 1, null, null);
        agreementSupport.drive(bystander.tenant(), bystander.caseId(), AgreementStatus.UNDER_REVIEW, 1, null, null);

        assertThat(titles(statusRows(participating.tenant(), participating.watcher())))
                .containsExactly("Fixture Agreement: Submitted for review");
        assertThat(statusRows(bystander.tenant(), bystander.watcher())).isEmpty();
    }

    @Test
    void aPortalOwnerIsNeverNotifiedEvenOncePortalVisible() {
        // A portal contact -- of ANOTHER customer -- set as the agreement's owner: the audience
        // (own customer, SENT onward) refuses them before SENT and cross-customer always, and
        // RecipientAccess refuses every non-INTERNAL recipient outright. Nothing reaches them.
        var a = arrange("agr-notify-portal", watcherGrants());
        UUID otherCustomer = fixture.runAsReturning(a.tenant(),
                () -> fixture.createCustomer(a.tenant(), "Globex", null, null, null));
        UUID portalUser = fixture.createPortalUserForContact(a.tenant(), otherCustomer,
                "contact+" + Uuid7.generate() + "@portal.example");
        ownerJdbc().update("update agreement set owner_user_id = ? where id = ?", portalUser, a.agreementId());

        var d = agreementSupport.drive(a.tenant(), a.caseId(), AgreementStatus.SENT, 1, null, null);
        recordSignature(a.tenant(), d, d.signatoryIds().get(0), d.lockVersion());

        assertThat(support.rowsFor(a.tenant(), portalUser)).isEmpty();
        // Positive control: the internal case owner in the same tenant still hears every transition.
        assertThat(statusRows(a.tenant(), a.watcher())).hasSize(4);
    }
}
