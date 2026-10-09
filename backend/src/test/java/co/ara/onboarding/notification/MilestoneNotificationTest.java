package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.ApprovalService;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneService;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** MILESTONE_COMPLETED (6B spec 5.2): the case audience, never the completer, gated by case.view. */
class MilestoneNotificationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired RequirementService requirements;
    @Autowired MilestoneService milestoneService;
    @Autowired ApprovalService approvals;
    @Autowired NotificationTestSupport support;

    private static final Map<String, Scope> COMPLETER = Map.of(
            PermissionKeys.MILESTONE_COMPLETE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
            PermissionKeys.WORKFLOW_VIEW, Scope.ALL);

    private record Arranged(UUID t, UUID caseId, UUID owner, UUID participant) {}

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        if (!grants.isEmpty()) support.grant(t, u, grants);
        return u;
    }

    /** A case owned by {@code owner} (granted {@code ownerGrants}) with one participant in {@code status}. */
    private Arranged arrange(String slug, Map<String, Scope> ownerGrants, ParticipantStatus status) {
        UUID t = fixture.createTenant(slug);
        UUID owner = user(t, "owner@" + slug + ".test", ownerGrants);
        UUID caseId = fixture.runAsReturning(t, () -> sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID participant = user(t, "pat@" + slug + ".test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        fixture.runAs(t, () -> journey.addParticipant(t, caseId, participant, RelationshipType.PARTICIPANT, status));
        return new Arranged(t, caseId, owner, participant);
    }

    private void satisfyFirstRequirement(Arranged a, UUID actor) {
        UUID rid = fixture.runAsReturning(a.t(), () -> sla.firstRequirementId(a.caseId()));
        fixture.runAsUser(a.t(), actor, () -> requirements.satisfy(rid, null, null));
    }

    private List<Map<String, Object>> completions(UUID t, UUID recipient) {
        return support.rowsFor(t, recipient).stream()
                .filter(r -> "MILESTONE_COMPLETED".equals(r.get("type"))).toList();
    }

    @Test
    void completingAMilestoneNotifiesTheCaseAudience() {
        var a = arrange("mn-audience", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL), ParticipantStatus.ACTIVE);
        UUID dave = user(a.t(), "dave@mn-audience.test", COMPLETER);
        UUID milestoneId = fixture.runAsReturning(a.t(), () -> sla.milestoneIdAt(a.caseId(), 0));

        satisfyFirstRequirement(a, dave);

        var toOwner = completions(a.t(), a.owner());
        assertThat(toOwner).hasSize(1);
        var n = toOwner.get(0);
        assertThat(n.get("subject_type")).isEqualTo("milestone");
        assertThat(n.get("subject_id")).isEqualTo(milestoneId);
        assertThat(n.get("case_id")).isEqualTo(a.caseId());
        assertThat(n.get("tone")).isEqualTo("OK");
        assertThat(n.get("title")).isEqualTo("Milestone completed: Milestone m1");
        assertThat(n.get("body")).isEqualTo("SLA case (Acme)");
        assertThat(completions(a.t(), a.participant())).hasSize(1);
        assertThat(completions(a.t(), dave)).isEmpty();
    }

    @Test
    void theCompleterIsNotNotified() {
        var a = arrange("mn-self", COMPLETER, ParticipantStatus.ACTIVE);

        satisfyFirstRequirement(a, a.owner());

        assertThat(completions(a.t(), a.owner())).isEmpty();
        assertThat(completions(a.t(), a.participant())).hasSize(1);
    }

    @Test
    void aForcedCompletionSaysSo() {
        var a = arrange("mn-forced", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL), ParticipantStatus.ACTIVE);
        UUID requester = user(a.t(), "req@mn-forced.test", Map.of(
                PermissionKeys.CASE_VIEW, Scope.ALL, PermissionKeys.WORKFLOW_VIEW, Scope.ALL,
                PermissionKeys.MILESTONE_FORCE_COMPLETE, Scope.ALL));
        UUID approver = user(a.t(), "app@mn-forced.test", Map.of(PermissionKeys.MILESTONE_FORCE_APPROVE, Scope.ALL));
        UUID milestoneId = fixture.runAsReturning(a.t(), () -> sla.milestoneIdAt(a.caseId(), 0));

        UUID[] approvalId = new UUID[1];
        fixture.runAsUser(a.t(), requester, () ->
                approvalId[0] = milestoneService.requestForceComplete(milestoneId, "Customer verbally confirmed").id());
        fixture.runAsUser(a.t(), approver, () -> approvals.decideForceComplete(approvalId[0], true, "confirmed"));

        // Published once, from ApprovalService -- markDone's first-time branch never fires for it.
        var toOwner = completions(a.t(), a.owner());
        assertThat(toOwner).hasSize(1);
        assertThat((String) toOwner.get(0).get("title")).startsWith("Milestone force-completed:");
        assertThat(toOwner.get(0).get("title")).isEqualTo("Milestone force-completed: Milestone m1");
        assertThat(completions(a.t(), a.participant())).hasSize(1);
        assertThat(completions(a.t(), approver)).isEmpty();
    }

    @Test
    void aRemovedParticipantIsNotNotified() {
        var a = arrange("mn-removed", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL), ParticipantStatus.REMOVED);
        UUID dave = user(a.t(), "dave@mn-removed.test", COMPLETER);

        satisfyFirstRequirement(a, dave);

        assertThat(completions(a.t(), a.participant())).isEmpty();
        assertThat(completions(a.t(), a.owner())).hasSize(1);
    }

    @Test
    void anOwnerHoldingCaseViewOnlyAtAssignedScopeIsNotified() {
        var a = arrange("mn-assigned", Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED), ParticipantStatus.ACTIVE);
        UUID dave = user(a.t(), "dave@mn-assigned.test", COMPLETER);

        satisfyFirstRequirement(a, dave);

        assertThat(completions(a.t(), a.owner())).hasSize(1);
    }

    @Test
    void anAudienceMemberWithoutCaseViewIsNotNotified() {
        UUID t = fixture.createTenant("mn-noview");
        UUID owner = user(t, "owner@mn-noview.test", Map.of());
        UUID caseId = fixture.runAsReturning(t, () -> sla.openFor(fixture.createCustomerOwnedBy(t, "Acme", owner)));
        UUID dave = user(t, "dave@mn-noview.test", COMPLETER);
        var a = new Arranged(t, caseId, owner, null);

        satisfyFirstRequirement(a, dave);

        assertThat(completions(t, owner)).isEmpty();
    }
}
