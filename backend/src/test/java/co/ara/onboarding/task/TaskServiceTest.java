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
 * Task 16: create ad-hoc, read, and the write-path guards. Every id TaskService
 * takes from a URL or a request body -- caseId, milestoneId -- is resolved
 * through AuthorizedQuery before a write, the same escalation shape
 * RequirementService's own doc comment names, and the one that bit
 * sub-project 1 three times (contact creation, role assignment, invitation
 * issuance).
 */
class TaskServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired RoleService roles;

    @Test
    void anAdHocTaskHasNoRequirementAndDoesNotTouchProgress() {
        UUID tenant = fixture.createTenant("task-adhoc");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID assigneeId = fixture.createUser(tenant, "assignee@example.com");

            var before = cases.get(caseId).progressPercent();
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.MEDIUM, assigneeId, LocalDate.now().plusDays(3))).id();

            assertThat(tasks.get(taskId).requirementId()).isNull();
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
        });
    }

    @Test
    void aTaskInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("task-tenant-a");
        UUID tenantB = fixture.createTenant("task-tenant-b");
        var taskId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            UUID caseId = simpleCase(tenantA);
            UUID milestoneId = firstMilestone(caseId);
            taskId[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Tenant A task", null,
                    TaskPriority.LOW, null, null)).id();
        });

        // Never assert inside the runAs lambda -- see CaseCreationTest's own note:
        // catching there leaves the transaction rollback-only and surfaces
        // UnexpectedRollbackException instead of the exception under test.
        assertThatThrownBy(() -> fixture.runAs(tenantB, () -> tasks.get(taskId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The escalation shape that bit sub-project 1 three times. */
    @Test
    void aMilestoneIdFromTheRequestBodyIsResolvedBeforeItIsWritten() {
        UUID tenantA = fixture.createTenant("task-escalation-a");
        UUID tenantB = fixture.createTenant("task-escalation-b");
        var caseId = new UUID[1];
        var anotherTenantsMilestoneId = new UUID[1];
        fixture.runAs(tenantA, () -> caseId[0] = simpleCase(tenantA));
        fixture.runAs(tenantB, () -> anotherTenantsMilestoneId[0] = firstMilestone(simpleCase(tenantB)));

        assertThatThrownBy(() -> fixture.runAs(tenantA, () -> tasks.create(caseId[0],
                new CreateTaskRequest(anotherTenantsMilestoneId[0], null, "Escalation attempt", null,
                        TaskPriority.LOW, null, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Finding 1 (review round 1): {@code update} used to check write_scope only
     * against the request's destination milestone, never the task's own current
     * (source) milestone. That let a DEPARTMENT-scoped TASK_MANAGE holder -- no
     * ASSIGNED relationship, no ownership of anything -- pull a task out of an
     * OWNER_ONLY stage they cannot write in by naming, as the destination, any
     * permissive milestone reachable under the same department scope. Shape
     * borrowed from journey.WriteScopeTest.allScopeIsStillRefusedInAnOwnerOnlyStage:
     * a case owned (case- and milestone-wise) by someone else, a narrow actor who
     * is only ever granted DEPARTMENT scope, and the assertion that the widest
     * *reachable* grant still doesn't rescue a write into a stage that refuses it.
     */
    @Test
    void updateRefusesMovingATaskOutOfAnOwnerOnlyStageThroughAPermissiveDestination() {
        UUID tenant = fixture.createTenant("task-move-escalation");
        var narrowActor = new UUID[1];
        var taskId = new UUID[1];
        var permissiveMilestoneId = new UUID[1];

        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Owning Department");
            narrowActor[0] = fixture.createUserInDepartment(tenant, "narrow@example.com", department);
            // TASK_MANAGE at DEPARTMENT only -- no ASSIGNED, no ownership, exactly the
            // grant the attack in Finding 1 needs nothing beyond. WORKFLOW_VIEW is
            // ALL_ONLY in the catalog, so it cannot be granted at DEPARTMENT at all --
            // stageOf()'s reads need it regardless of the actor being tested.
            grant(narrowActor[0], Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            // The actor is neither the case's nor the milestone's owner -- only a
            // fellow department member.
            UUID caseOwner = fixture.createUser(tenant, "owner@example.com");
            grant(caseOwner, Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
            UUID customerId = fixture.createCustomer(
                    tenant, "Acme " + Uuid7.generate(), caseOwner, department, null);

            var restrictedStage = new WorkflowDefinitionRequest.StageRequest(
                    "s1", "Restricted Stage", null, false, true, true, null,
                    WriteScope.OWNER_ONLY, null, null, null,
                    List.of(milestone("m1", "Milestone One", 1, List.of(), List.of(manual("Do it")))),
                    List.of());
            var permissiveStage = stage("s2", "Permissive Stage", List.of(
                    milestone("m2", "Milestone Two", 1, List.of(), List.of(manual("Do it")))));
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                    List.of(restrictedStage, permissiveStage), List.of(), 0L));

            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();

            var stages = cases.roadmap(caseId).stages();
            UUID restrictedMilestoneId = stages.get(0).milestones().get(0).id();
            permissiveMilestoneId[0] = stages.get(1).milestones().get(0).id();

            // Seeded by the case owner -- the only identity that can legitimately
            // write into the OWNER_ONLY stage in the first place.
            fixture.runAsUser(tenant, caseOwner, () -> taskId[0] = tasks.create(caseId,
                    new CreateTaskRequest(restrictedMilestoneId, null, "Restricted task", null,
                            TaskPriority.MEDIUM, null, null)).id());
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor[0], () -> tasks.update(taskId[0],
                new UpdateTaskRequest("Renamed", null, TaskPriority.HIGH, null, null,
                        permissiveMilestoneId[0]))))
                .isInstanceOf(WriteScopeException.class);
    }

    /**
     * Finding 2 (review round 1): {@code create}'s case/milestone mismatch check
     * IS present and correctly ordered before writeScope.check, but the brief's
     * own test for "a milestoneId from the request body is resolved before it is
     * written" only exercises a cross-tenant milestone, which RLS alone already
     * catches. This exercises the scenario that actually depends on the mismatch
     * check: a same-tenant milestone the actor CAN resolve under TASK_MANAGE, but
     * which belongs to a different case than the URL's caseId.
     */
    @Test
    void createRefusesASameTenantMilestoneThatBelongsToADifferentCase() {
        UUID tenant = fixture.createTenant("task-cross-case");
        var caseAId = new UUID[1];
        var caseBMilestoneId = new UUID[1];
        fixture.runAs(tenant, () -> {
            caseAId[0] = simpleCase(tenant);
            caseBMilestoneId[0] = firstMilestone(simpleCase(tenant));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> tasks.create(caseAId[0],
                new CreateTaskRequest(caseBMilestoneId[0], null, "Cross-case attempt", null,
                        TaskPriority.LOW, null, null))))
                .isInstanceOf(NoSuchElementException.class);
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
