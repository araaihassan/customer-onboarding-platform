package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.WriteScopeException;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 25: the two write-scope negatives the brief requires, proving what
 * Tasks 16/17 already built rather than adding new production logic --
 * "at least one write test at the narrowest scope" (CLAUDE.md) for
 * task.complete, and the write-scope guard's own subtractive rule applied to
 * a task rather than a requirement (journey.WriteScopeTest's own shape,
 * carried over here for TaskService.changeStatus since journey has no
 * equivalent caller of it).
 */
class TaskWriteScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired RoleService roles;

    /**
     * ASSIGNED is a personal relationship (CLAUDE.md's RelationshipType
     * invariant): TaskDescriptor.assignedScope compares assignee_id to the
     * ACTING user directly, never through case_participant or a team. An
     * actor holding task.complete at ASSIGNED only, attempting to complete a
     * task assigned to someone else, finds nothing to resolve -- the same
     * NoSuchElementException/404 shape every other out-of-scope record gets.
     */
    @Test
    void anAssignedScopeHolderCannotCompleteSomeoneElsesTask() {
        UUID tenant = fixture.createTenant("task-ws-assigned");
        var assignedScopeUser = new UUID[1];
        var taskAssignedToAnother = new UUID[1];
        fixture.runAs(tenant, () -> {
            assignedScopeUser[0] = fixture.createUser(tenant, "assigned-scope@example.com");
            grant(assignedScopeUser[0], Map.of(PermissionKeys.TASK_COMPLETE, Scope.ASSIGNED));

            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID someoneElse = fixture.createUser(tenant, "someone-else@example.com");
            taskAssignedToAnother[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Someone else's task", null,
                    TaskPriority.MEDIUM, someoneElse, null)).id();
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, assignedScopeUser[0], () ->
                tasks.changeStatus(taskAssignedToAnother[0],
                        new TaskStatusRequest(TaskStatus.COMPLETED, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * The widest possible record-level grant (ALL, on both task.manage and
     * task.complete) is still refused inside an OWNER_ONLY stage the actor
     * has no ownership relationship to -- StageWriteScopeGuard narrows on top
     * of, never instead of, the record-level scope a permission is held at.
     * The task itself is seeded by the case owner, the only identity able to
     * legitimately write into the restricted stage in the first place -- same
     * shape as TaskServiceTest's own OWNER_ONLY escalation test.
     */
    @Test
    void aWiderScopedHolderIsStillRefusedInsideAnOwnerOnlyStage() {
        UUID tenant = fixture.createTenant("task-ws-owner-only");
        var allScopeNonOwner = new UUID[1];
        var taskInOwnerOnlyStage = new UUID[1];
        fixture.runAs(tenant, () -> {
            allScopeNonOwner[0] = fixture.createUser(tenant, "wide@example.com");
            grant(allScopeNonOwner[0], Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.ALL,
                    PermissionKeys.TASK_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseOwner = fixture.createUser(tenant, "owner@example.com");
            grant(caseOwner, Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            UUID versionId = journey.publish(
                    new WorkflowDefinitionRequest(List.of(restrictedStage), List.of(), 0L));

            UUID customerId = fixture.createCustomer(
                    tenant, "Acme " + Uuid7.generate(), caseOwner, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            UUID milestoneId = cases.roadmap(caseId).stages().get(0).milestones().get(0).id();

            // Seeded by the case owner -- the only identity that can legitimately
            // write into the OWNER_ONLY stage at all.
            fixture.runAsUser(tenant, caseOwner, () -> taskInOwnerOnlyStage[0] = tasks.create(caseId,
                    new CreateTaskRequest(milestoneId, null, "Owner-only task", null,
                            TaskPriority.MEDIUM, null, null)).id());
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, allScopeNonOwner[0], () ->
                tasks.changeStatus(taskInOwnerOnlyStage[0],
                        new TaskStatusRequest(TaskStatus.IN_PROGRESS, null))))
                .isInstanceOf(WriteScopeException.class);
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstMilestone(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }
}
