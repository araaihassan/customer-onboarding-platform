package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Q15: "an authorized user can reassign a milestone to any user". Run under
 * PostgresTestBase directly, not SecurityTestBase's MockMvc convention -- journey
 * has no HTTP layer yet (Task 20), matching the deviation WriteScopeTest already
 * documented for the same reason.
 */
class MilestoneEditTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired MilestoneService milestones;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;

    /**
     * The invariant that makes reassignment usable rather than a trap: assigning
     * an owner also creates their ASSIGNEE participant row. Without it the new
     * owner holds a milestone inside a case they cannot open.
     */
    @Test
    void assigningAnOwnerAlsoMakesThemACaseParticipant() {
        UUID tenant = fixture.createTenant("ms-assign");
        var outsider = new UUID[1];
        var caseId = new UUID[1];
        fixture.runAs(tenant, () -> {
            outsider[0] = fixture.createUser(tenant, "specialist@example.com");
            // WORKFLOW_VIEW ALL alongside CASE_VIEW: cases.get()'s toView reads the
            // case's pinned Stage for its name, gated on WORKFLOW_VIEW, not CASE_VIEW --
            // same invariant CaseEditTest documents for the same reason.
            grantAll(outsider[0], Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED,
                    PermissionKeys.MILESTONE_COMPLETE, Scope.ASSIGNED,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            caseId[0] = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId[0]).id();

            milestones.update(milestoneId, new UpdateMilestoneRequest(outsider[0], null));
        });

        // The point of the invariant: they can now actually see the case.
        fixture.runAsUser(tenant, outsider[0], () ->
                assertThat(cases.get(caseId[0])).isNotNull());
    }

    @Test
    void reschedulingAMilestoneMovesOnlyThatDueDate() {
        UUID tenant = fixture.createTenant("ms-reschedule");
        fixture.runAs(tenant, () -> {
            UUID caseId = twoMilestoneCase(tenant);
            UUID firstId = milestoneAt(caseId, 0).id();
            LocalDate secondDueBefore = milestoneAt(caseId, 1).dueDate();

            milestones.update(firstId, new UpdateMilestoneRequest(null, LocalDate.of(2030, 1, 1)));

            assertThat(milestoneAt(caseId, 0).dueDate()).isEqualTo(LocalDate.of(2030, 1, 1));
            assertThat(milestoneAt(caseId, 1).dueDate()).isEqualTo(secondDueBefore);
        });
    }

    @Test
    void aUserFromAnotherTenantCannotBeAssigned() {
        UUID tenantA = fixture.createTenant("ms-tenant-a");
        UUID tenantB = fixture.createTenant("ms-tenant-b");
        var milestoneId = new UUID[1];
        var tenantBUserId = new UUID[1];
        fixture.runAs(tenantA, () -> milestoneId[0] = firstMilestone(simpleCase(tenantA)).id());
        fixture.runAs(tenantB, () -> tenantBUserId[0] = fixture.createUser(tenantB, "other@example.com"));

        // Never assert inside the runAs lambda -- see CaseCreationTest's own note.
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                milestones.update(milestoneId[0], new UpdateMilestoneRequest(tenantBUserId[0], null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void reassignmentRequiresMilestoneEditAndIsAudited() {
        UUID tenant = fixture.createTenant("ms-audit");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId).id();
            UUID newOwner = fixture.createUser(tenant, "newowner@example.com");

            milestones.update(milestoneId, new UpdateMilestoneRequest(newOwner, null));

            assertThat(auditEvents.findAll()).anySatisfy(e ->
                    assertThat(e.getAction()).isEqualTo(AuditActions.MILESTONE_REASSIGNED.key()));
        });

        // The permission side: an actor with no milestone.edit grant at all is
        // refused by the gate itself, before write_scope is ever consulted.
        var ungranted = new UUID[1];
        var milestoneId = new UUID[1];
        fixture.runAs(tenant, () -> {
            ungranted[0] = fixture.createUser(tenant, "ungranted@example.com");
            milestoneId[0] = firstMilestone(simpleCase(tenant)).id();
        });
        assertThatThrownBy(() -> fixture.runAsUser(tenant, ungranted[0], () ->
                milestones.update(milestoneId[0], new UpdateMilestoneRequest(null, LocalDate.of(2030, 1, 1)))))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test
    void reassignmentIsRefusedByAnOwnerOnlyStageForANonOwner() {
        UUID tenant = fixture.createTenant("ms-write-scope");
        var actor = new UUID[1];
        var milestoneId = new UUID[1];
        fixture.runAs(tenant, () -> {
            actor[0] = fixture.createUser(tenant, "notowner@example.com");
            // WORKFLOW_VIEW ALL: update()'s own stageOf(m) lookup resolves the
            // milestone's Stage under WORKFLOW_VIEW before write_scope ever runs --
            // without it the actor 404s here rather than hitting WriteScopeException.
            grantAll(actor[0], Map.of(PermissionKeys.MILESTONE_EDIT, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "realowner@example.com");
            milestoneId[0] = firstMilestone(ownerOnlyCase(tenant, caseOwner)).id();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, actor[0], () ->
                milestones.update(milestoneId[0], new UpdateMilestoneRequest(null, LocalDate.of(2030, 1, 1)))))
                .isInstanceOf(WriteScopeException.class);
    }

    private void grantAll(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.publishedTemplate(), Map.of())).id();
    }

    private UUID twoMilestoneCase(UUID tenant) {
        var stageRequest = stage("s1", "Stage One", List.of(
                milestone("m1", "Milestone One", 2, List.of(), List.of(manual("Do it"))),
                milestone("m2", "Milestone Two", 3, List.of(), List.of(manual("Do it")))));
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of())).id();
    }

    /** A single-stage, single-milestone, OWNER_ONLY-write-scope case owned by the given user. */
    private UUID ownerOnlyCase(UUID tenant, UUID ownerUserId) {
        var stageRequest = new WorkflowDefinitionRequest.StageRequest(
                "s1", "Stage One", null, false, true, true, null, WriteScope.OWNER_ONLY, null,
                null, null,
                List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                List.of());
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(List.of(stageRequest), List.of(), 0L));
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), ownerUserId, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), Map.of())).id();
    }

    private MilestoneRoadmapView firstMilestone(UUID caseId) {
        return milestoneAt(caseId, 0);
    }

    private MilestoneRoadmapView milestoneAt(UUID caseId, int index) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(index);
    }
}
