package co.ara.onboarding.journey;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowFixtures;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * decideStageExit's functional coverage -- approve advances the held stage,
 * reject leaves the case in place, and an already-decided approval refuses a
 * second decision. Q5's self-approval/kind-confusion negatives live in
 * ForceCompleteTest, where they belong alongside the rest of that requirement.
 */
class ApprovalTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired ApprovalService approvals;
    @Autowired ApprovalRepository approvalRepository;
    @Autowired RoleService roles;

    @Test
    void approvingAPendingStageExitAdvancesTheCase() {
        UUID tenant = fixture.createTenant("appr-approve");
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "approver@example.com");
            grantAll(actor[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_COMPLETE,
                    PermissionKeys.APPROVAL_DECIDE);
            caseId[0] = twoStageCaseAwaitingApproval(tenant);
        });

        fixture.runAsUser(tenant, actor[0], () -> {
            // Satisfy stage one's only requirement -- the stage is now exitable but
            // gated, so reconcile parked a PENDING STAGE_EXIT approval instead of moving on.
            UUID requirementId = firstRequirementId(caseId[0]);
            requirements.satisfy(requirementId, null, null);

            UUID stageOneId = cases.roadmap(caseId[0]).stages().get(0).id();
            UUID approvalId = pendingStageExitApprovalFor(caseId[0], stageOneId);

            var view = approvals.decideStageExit(approvalId, true, "Looks good");
            assertThat(view.status()).isEqualTo(ApprovalStatus.APPROVED);

            var roadmap = cases.roadmap(caseId[0]);
            assertThat(cases.get(caseId[0]).currentStageId()).isEqualTo(roadmap.stages().get(1).id());
        });
    }

    @Test
    void rejectingAPendingStageExitLeavesTheCaseInPlace() {
        UUID tenant = fixture.createTenant("appr-reject");
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "rejecter@example.com");
            grantAll(actor[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_COMPLETE,
                    PermissionKeys.APPROVAL_DECIDE);
            caseId[0] = twoStageCaseAwaitingApproval(tenant);
        });

        fixture.runAsUser(tenant, actor[0], () -> {
            requirements.satisfy(firstRequirementId(caseId[0]), null, null);
            UUID stageOneId = cases.roadmap(caseId[0]).stages().get(0).id();
            UUID approvalId = pendingStageExitApprovalFor(caseId[0], stageOneId);

            var view = approvals.decideStageExit(approvalId, false, "Not yet");
            assertThat(view.status()).isEqualTo(ApprovalStatus.REJECTED);
            assertThat(cases.get(caseId[0]).currentStageId()).isEqualTo(stageOneId);
        });
    }

    @Test
    void decidingAnAlreadyDecidedApprovalIsRefused() {
        UUID tenant = fixture.createTenant("appr-twice");
        var actor = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "twice@example.com");
            grantAll(actor[0], PermissionKeys.CASE_CREATE, PermissionKeys.CASE_VIEW,
                    PermissionKeys.WORKFLOW_VIEW, PermissionKeys.MILESTONE_COMPLETE,
                    PermissionKeys.APPROVAL_DECIDE);
            caseId[0] = twoStageCaseAwaitingApproval(tenant);
        });

        fixture.runAsUser(tenant, actor[0], () -> {
            requirements.satisfy(firstRequirementId(caseId[0]), null, null);
            UUID stageOneId = cases.roadmap(caseId[0]).stages().get(0).id();
            UUID approvalId = pendingStageExitApprovalFor(caseId[0], stageOneId);
            approvals.decideStageExit(approvalId, true, "ok");
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                approvals.decideStageExit(pendingOrDecidedApprovalId(tenant, caseId[0]), true, "again")))
                .isInstanceOf(ApprovalAlreadyDecidedException.class);
    }

    private void grantAll(UUID userId, String... permissionKeys) {
        Map<String, Scope> grants = new HashMap<>();
        for (String key : permissionKeys) grants.put(key, Scope.ALL);
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    /** Two stages, one milestone each; stage one requires approval and auto-advances once approved. */
    private UUID twoStageCaseAwaitingApproval(UUID tenant) {
        var stageOne = new WorkflowDefinitionRequest.StageRequest(
                "s1", "Stage One", null, true, true, true, null, WriteScope.ANY, null,
                null, null,
                List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                List.of());
        var stageTwo = WorkflowFixtures.stage("s2", "Stage Two", List.of(
                milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))));
        UUID versionId = journey.publish(
                new WorkflowDefinitionRequest(List.of(stageOne, stageTwo), List.of(), 0L));

        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }

    private UUID pendingStageExitApprovalFor(UUID caseId, UUID stageId) {
        return approvalRepository.findByCaseIdAndStatus(caseId, ApprovalStatus.PENDING).stream()
                .filter(a -> a.getKind() == ApprovalKind.STAGE_EXIT && stageId.equals(a.getStageId()))
                .findFirst().orElseThrow().getId();
    }

    /** After the first decision the row is no longer PENDING; fetch it by whatever status it now holds. */
    private UUID pendingOrDecidedApprovalId(UUID tenant, UUID caseId) {
        return approvalRepository.findByCaseIdAndStatus(caseId, ApprovalStatus.APPROVED).stream()
                .findFirst().map(Approval::getId).orElseThrow();
    }
}
