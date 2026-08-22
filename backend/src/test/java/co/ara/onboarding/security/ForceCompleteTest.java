package co.ara.onboarding.security;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.InvalidGrantException;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.ApprovalAlreadyDecidedException;
import co.ara.onboarding.journey.ApprovalKindMismatchException;
import co.ara.onboarding.journey.ApprovalRepository;
import co.ara.onboarding.journey.ApprovalService;
import co.ara.onboarding.journey.ApprovalStatus;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.MilestoneService;
import co.ara.onboarding.journey.MilestoneStatus;
import co.ara.onboarding.journey.SelfApprovalException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q5's three requirements, as three separate mechanisms, plus the kind-confusion
 * negatives that make the two decide paths structurally non-interchangeable.
 *
 * Extends PostgresTestBase rather than SecurityTestBase's MockMvc convention:
 * journey has no HTTP layer yet (Task 20 builds it), so there is no endpoint for
 * that convention to drive -- the same deviation WriteScopeTest already documents
 * for Task 16.
 */
class ForceCompleteTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired MilestoneService milestones;
    @Autowired ApprovalService approvals;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired MilestoneRepository milestoneRepository;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void aForceRequestWithoutAReasonIsRejected() {
        UUID tenant = fixture.createTenant("force-blank");
        var actor = new UUID[1];
        var milestoneId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "requester@example.com");
            grantAll(actor[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE);
            milestoneId[0] = firstMilestoneId(caseWithOneMilestone(tenant));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                milestones.requestForceComplete(milestoneId[0], "   ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRequesterCannotApproveTheirOwnForceRequest() {
        UUID tenant = fixture.createTenant("force-self");
        var admin = new UUID[1];          // holds BOTH permissions
        var approvalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            admin[0] = fixture.createUser(tenant, "boss@example.com");
            grantAll(admin[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE,
                    PermissionKeys.MILESTONE_FORCE_APPROVE);
        });

        var mid = new UUID[1];
        fixture.runAs(tenant, () -> mid[0] = firstMilestoneId(caseWithOneMilestone(tenant)));

        fixture.runAsUser(tenant, admin[0], () ->
                approvalId[0] = milestones.requestForceComplete(mid[0], "Customer verbally confirmed").id());

        assertThatThrownBy(() -> fixture.runAsUser(tenant, admin[0], () ->
                approvals.decideForceComplete(approvalId[0], true, "ok")))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    void anApprovedForceCompletionIsAuditedAsForcedWithItsReason() {
        UUID tenant = fixture.createTenant("force-audit");
        var requester = new UUID[1];
        var approver = new UUID[1];
        var milestoneId = new UUID[1];
        var approvalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            requester[0] = fixture.createUser(tenant, "req@example.com");
            approver[0] = fixture.createUser(tenant, "app@example.com");
            grantAll(requester[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE);
            grantAll(approver[0], PermissionKeys.MILESTONE_FORCE_APPROVE);
            milestoneId[0] = firstMilestoneId(caseWithOneMilestone(tenant));
        });

        fixture.runAsUser(tenant, requester[0], () ->
                approvalId[0] = milestones.requestForceComplete(milestoneId[0], "Customer verbally confirmed").id());

        fixture.runAsUser(tenant, approver[0], () ->
                approvals.decideForceComplete(approvalId[0], true, "confirmed"));

        fixture.runAs(tenant, () -> {
            assertThat(auditEventsFor("milestone.force_completed")).hasSize(1);
            Milestone m = milestoneRepository.findById(milestoneId[0]).orElseThrow();
            assertThat(m.getStatus()).isEqualTo(MilestoneStatus.DONE);
            assertThat(m.getCompletionReason()).isEqualTo("Customer verbally confirmed");
        });
    }

    @Test
    void theForceApprovePermissionCannotBeGrantedBelowAllScope() {
        UUID tenant = fixture.createTenant("force-scope");
        assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.createRole("Sneaky", "",
                Map.of(PermissionKeys.MILESTONE_FORCE_APPROVE, Scope.TEAM))))
                .isInstanceOf(InvalidGrantException.class);
    }

    @Test
    void aForceRequestCannotBeDecidedThroughTheStageExitPath() {
        UUID tenant = fixture.createTenant("force-kind-a");
        var requester = new UUID[1];
        var decider = new UUID[1];
        var milestoneId = new UUID[1];
        var forceApprovalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            requester[0] = fixture.createUser(tenant, "req2@example.com");
            decider[0] = fixture.createUser(tenant, "decider2@example.com");
            grantAll(requester[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE);
            grantAll(decider[0], PermissionKeys.APPROVAL_DECIDE);
            milestoneId[0] = firstMilestoneId(caseWithOneMilestone(tenant));
        });

        fixture.runAsUser(tenant, requester[0], () ->
                forceApprovalId[0] = milestones.requestForceComplete(milestoneId[0], "Reason").id());

        assertThatThrownBy(() -> fixture.runAsUser(tenant, decider[0], () ->
                approvals.decideStageExit(forceApprovalId[0], true, "ok")))
                .isInstanceOf(ApprovalKindMismatchException.class);
    }

    @Test
    void aStageExitCannotBeDecidedThroughTheForcePath() {
        UUID tenant = fixture.createTenant("force-kind-b");
        var caseOwner = new UUID[1];
        var approver = new UUID[1];
        var stageExitApprovalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseOwner[0] = fixture.createUser(tenant, "owner3@example.com");
            approver[0] = fixture.createUser(tenant, "approver3@example.com");
            grantAll(approver[0], PermissionKeys.MILESTONE_FORCE_APPROVE);
            UUID caseId = caseWithOneMilestone(tenant);
            UUID stageId = cases.roadmap(caseId).stages().get(0).id();
            stageExitApprovalId[0] = journey.newApproval(tenant, caseId, stageId, caseOwner[0]).getId();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, approver[0], () ->
                approvals.decideForceComplete(stageExitApprovalId[0], true, "ok")))
                .isInstanceOf(ApprovalKindMismatchException.class);
    }

    @Test
    void aRejectedForceRequestLeavesTheMilestoneAlone() {
        UUID tenant = fixture.createTenant("force-reject");
        var requester = new UUID[1];
        var approver = new UUID[1];
        var milestoneId = new UUID[1];
        var approvalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            requester[0] = fixture.createUser(tenant, "req4@example.com");
            approver[0] = fixture.createUser(tenant, "app4@example.com");
            grantAll(requester[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE);
            grantAll(approver[0], PermissionKeys.MILESTONE_FORCE_APPROVE);
            milestoneId[0] = firstMilestoneId(caseWithOneMilestone(tenant));
        });

        fixture.runAsUser(tenant, requester[0], () ->
                approvalId[0] = milestones.requestForceComplete(milestoneId[0], "Reason").id());

        fixture.runAsUser(tenant, approver[0], () -> {
            var view = approvals.decideForceComplete(approvalId[0], false, "Not enough evidence");
            assertThat(view.status()).isEqualTo(ApprovalStatus.REJECTED);
        });

        fixture.runAs(tenant, () -> {
            Milestone m = milestoneRepository.findById(milestoneId[0]).orElseThrow();
            assertThat(m.getStatus()).isNotEqualTo(MilestoneStatus.DONE);
            assertThat(auditEventsFor("milestone.force_rejected")).hasSize(1);
        });
    }

    /** Also verifies decidingAnAlreadyDecidedApprovalIsRefused for the FORCE_COMPLETE path. */
    @Test
    void decidingAnAlreadyDecidedForceRequestIsRefused() {
        UUID tenant = fixture.createTenant("force-twice");
        var requester = new UUID[1];
        var approver = new UUID[1];
        var milestoneId = new UUID[1];
        var approvalId = new UUID[1];
        fixture.runAs(tenant, () -> {
            requester[0] = fixture.createUser(tenant, "req5@example.com");
            approver[0] = fixture.createUser(tenant, "app5@example.com");
            grantAll(requester[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_FORCE_COMPLETE);
            grantAll(approver[0], PermissionKeys.MILESTONE_FORCE_APPROVE);
            milestoneId[0] = firstMilestoneId(caseWithOneMilestone(tenant));
        });

        fixture.runAsUser(tenant, requester[0], () ->
                approvalId[0] = milestones.requestForceComplete(milestoneId[0], "Reason").id());

        fixture.runAsUser(tenant, approver[0], () -> approvals.decideForceComplete(approvalId[0], true, "ok"));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, approver[0], () ->
                approvals.decideForceComplete(approvalId[0], false, "too late")))
                .isInstanceOf(ApprovalAlreadyDecidedException.class);
    }

    private void grantAll(UUID userId, String... permissionKeys) {
        Map<String, Scope> grants = new HashMap<>();
        for (String key : permissionKeys) grants.put(key, Scope.ALL);
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    /** A single stage/milestone/requirement case, no approval gating. */
    private UUID caseWithOneMilestone(UUID tenant) {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, templateId, Map.of())).id();
    }

    private UUID firstMilestoneId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }

    private java.util.List<co.ara.onboarding.audit.AuditEvent> auditEventsFor(String action) {
        return auditEvents.findAll().stream().filter(e -> action.equals(e.getAction())).toList();
    }
}
