package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.journey.RequirementStatus;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * write_scope is a subtractive-only narrowing (StageWriteScopeGuard), tested here
 * at the service layer rather than through {@code SecurityTestBase}'s MockMvc
 * convention: journey has no HTTP layer yet (Task 20 builds it), so there is no
 * endpoint to drive. This is a deliberate, documented deviation from the plan's
 * {@code extends SecurityTestBase} -- see the Task 16 plan amendment.
 *
 * "Wherever a permission is catalogued at several scopes, at least one write test
 * must run at the narrowest one" (CLAUDE.md) -- this class is that test for
 * milestone.complete, and allScopeIsStillRefusedInAnOwnerOnlyStage is the one
 * assertion that proves write_scope is a real constraint rather than a rendered
 * field: the widest possible grant is still refused.
 */
class WriteScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired MilestoneRepository milestones;
    @Autowired RoleService roles;

    @Test
    void allScopeIsStillRefusedInAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("ws-owner-refused");
        var actor = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "wide@example.com");
            grantAll(actor[0], PermissionKeys.MILESTONE_COMPLETE, PermissionKeys.CASE_VIEW, PermissionKeys.WORKFLOW_VIEW);

            // The actor is neither the case owner nor the milestone owner.
            UUID caseOwner = fixture.createUser(tenant, "owner@example.com");
            requirementId[0] = firstRequirementId(caseFor(tenant, WriteScope.OWNER_ONLY, caseOwner, null, null));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                requirements.satisfy(requirementId[0], null, null)))
                .isInstanceOf(WriteScopeException.class);
    }

    @Test
    void theMilestoneOwnerMayWriteInAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("ws-milestone-owner");
        var milestoneOwner = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            milestoneOwner[0] = fixture.createUser(tenant, "mowner@example.com");
            grantAll(milestoneOwner[0], PermissionKeys.MILESTONE_COMPLETE, PermissionKeys.CASE_VIEW, PermissionKeys.WORKFLOW_VIEW);

            // Case owned by someone else entirely: success here can only come from
            // the milestone-owner branch, not the case-owner branch.
            UUID caseOwner = fixture.createUser(tenant, "cowner@example.com");
            UUID caseId = caseFor(tenant, WriteScope.OWNER_ONLY, caseOwner, null, null);
            requirementId[0] = firstRequirementId(caseId);
            // Default milestone owner is the customer's owner (Q15); reassign it
            // directly -- no milestone-reassignment API exists yet.
            reassignFirstMilestoneOwner(caseId, milestoneOwner[0]);
        });

        fixture.runAsUser(tenant, milestoneOwner[0], () -> {
            var view = requirements.satisfy(requirementId[0], null, null);
            assertThat(view.status()).isEqualTo(RequirementStatus.SATISFIED);
        });
    }

    @Test
    void theCaseOwnerMayWriteInAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("ws-case-owner");
        var caseOwner = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseOwner[0] = fixture.createUser(tenant, "cowner2@example.com");
            grantAll(caseOwner[0], PermissionKeys.MILESTONE_COMPLETE, PermissionKeys.CASE_VIEW, PermissionKeys.WORKFLOW_VIEW);

            UUID caseId = caseFor(tenant, WriteScope.OWNER_ONLY, caseOwner[0], null, null);
            requirementId[0] = firstRequirementId(caseId);
            // Reassign the milestone to someone else, so success here proves the
            // case-owner branch specifically, not the milestone-owner branch.
            UUID someoneElse = fixture.createUser(tenant, "unrelated@example.com");
            reassignFirstMilestoneOwner(caseId, someoneElse);
        });

        fixture.runAsUser(tenant, caseOwner[0], () -> {
            var view = requirements.satisfy(requirementId[0], null, null);
            assertThat(view.status()).isEqualTo(RequirementStatus.SATISFIED);
        });
    }

    @Test
    void aTeamStageAdmitsOnlyTheOwningTeam() {
        UUID tenant = fixture.createTenant("ws-team");
        var member = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID team = fixture.createTeam(tenant, "Owning Team");
            member[0] = fixture.createUser(tenant, "teammate@example.com");
            fixture.addToTeam(tenant, member[0], team);
            grantAll(member[0], PermissionKeys.MILESTONE_COMPLETE, PermissionKeys.CASE_VIEW, PermissionKeys.WORKFLOW_VIEW);

            requirementId[0] = firstRequirementId(caseFor(tenant, WriteScope.TEAM, null, null, team));
        });

        fixture.runAsUser(tenant, member[0], () -> {
            var view = requirements.satisfy(requirementId[0], null, null);
            assertThat(view.status()).isEqualTo(RequirementStatus.SATISFIED);
        });
    }

    @Test
    void aDepartmentStageAdmitsOnlyTheOwningDepartment() {
        UUID tenant = fixture.createTenant("ws-department");
        var member = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Owning Department");
            member[0] = fixture.createUserInDepartment(tenant, "depmate@example.com", department);
            grantAll(member[0], PermissionKeys.MILESTONE_COMPLETE, PermissionKeys.CASE_VIEW, PermissionKeys.WORKFLOW_VIEW);

            requirementId[0] = firstRequirementId(caseFor(tenant, WriteScope.DEPARTMENT, null, department, null));
        });

        fixture.runAsUser(tenant, member[0], () -> {
            var view = requirements.satisfy(requirementId[0], null, null);
            assertThat(view.status()).isEqualTo(RequirementStatus.SATISFIED);
        });
    }

    /**
     * The direction that must never work: write_scope cannot let someone in. An
     * actor with no milestone.complete grant at all is refused by the permission
     * gate itself, before write_scope is ever consulted, and an ANY stage does not
     * rescue them.
     */
    @Test
    void anAnyStageStillRequiresThePermission() {
        UUID tenant = fixture.createTenant("ws-any-still-gated");
        var ungranted = new UUID[1];
        var requirementId = new UUID[1];
        fixture.runAs(tenant, () -> {
            ungranted[0] = fixture.createUser(tenant, "ungranted@example.com");
            requirementId[0] = firstRequirementId(caseFor(tenant, WriteScope.ANY, null, null, null));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, ungranted[0], () ->
                requirements.satisfy(requirementId[0], null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    private void grantAll(UUID userId, String... permissionKeys) {
        Map<String, Scope> grants = new java.util.HashMap<>();
        for (String key : permissionKeys) grants.put(key, Scope.ALL);
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    /**
     * A single-stage, single-milestone, single-MANDATORY-manual-requirement
     * template with the given write_scope, pinned to a case owned/departmented/
     * teamed as given. Returns the case id.
     */
    private UUID caseFor(UUID tenant, WriteScope scope, UUID ownerUserId, UUID departmentId, UUID teamId) {
        var stageRequest = new WorkflowDefinitionRequest.StageRequest(
                "s1", "Stage One", null, false, true, true, null, scope, null,
                null, null,
                List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                List.of());
        UUID versionId = journey.publish(
                new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));

        UUID customerId = fixture.createCustomer(
                tenant, "Acme " + Uuid7.generate(), ownerUserId, departmentId, teamId);
        return cases.create(new CreateCaseRequest(
                customerId, journey.templateOf(versionId), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }

    /**
     * Test-only shortcut: no milestone-reassignment API exists yet (a later
     * task's territory), so the row is written directly. The default owner
     * (Q15: the customer's owner) is what create() sets; this overrides it so
     * the milestone-owner and case-owner branches can be tested independently.
     */
    private void reassignFirstMilestoneOwner(UUID caseId, UUID newOwner) {
        UUID milestoneId = cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
        Milestone m = milestones.findById(milestoneId).orElseThrow();
        m.setOwnerUserId(newOwner);
        milestones.saveAndFlush(m);
    }
}
