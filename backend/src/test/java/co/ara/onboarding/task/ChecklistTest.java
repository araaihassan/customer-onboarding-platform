package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.ParticipantStatus;
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
import java.util.NoSuchElementException;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 22: checklist items on a task. A private aid, never a second progress
 * mechanism -- the two tests the brief names verbatim
 * ({@link #checklistItemsNeverEnterProgress} and
 * {@link #everyItemDoneDoesNotCompleteTheTask}) prove exactly that. The rest
 * exercise ChecklistService's own write-path obligations: the task.manage /
 * task.complete gate split, the AuthorizedQuery + TaskChecklistItemDescriptor
 * scoping the item read now goes through, and StageWriteScopeGuard.
 */
class ChecklistTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired ChecklistService checklists;
    @Autowired RoleService roles;

    @Test
    void checklistItemsNeverEnterProgress() {
        UUID tenant = fixture.createTenant("checklist-progress");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Onboard the customer", null,
                    TaskPriority.MEDIUM, null, null)).id();

            var before = cases.get(caseId).progressPercent();
            UUID itemId = checklists.add(taskId, new AddChecklistItemRequest("Verify passport"));
            checklists.toggle(itemId);

            assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
        });
    }

    @Test
    void everyItemDoneDoesNotCompleteTheTask() {
        UUID tenant = fixture.createTenant("checklist-no-autocomplete");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Onboard the customer", null,
                    TaskPriority.MEDIUM, null, null)).id();
            UUID onlyItemId = checklists.add(taskId, new AddChecklistItemRequest("The only item"));

            checklists.toggle(onlyItemId);

            assertThat(tasks.get(taskId).status()).isEqualTo(TaskStatus.PENDING);
        });
    }

    @Test
    void addAssignsSequentialOrdinalsAcrossMultipleItems() {
        UUID tenant = fixture.createTenant("checklist-ordinals");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);

            UUID first = checklists.add(taskId, new AddChecklistItemRequest("First"));
            UUID second = checklists.add(taskId, new AddChecklistItemRequest("Second"));
            UUID third = checklists.add(taskId, new AddChecklistItemRequest("Third"));

            assertThat(checklists.rename(first, new RenameChecklistItemRequest("First")).ordinal()).isZero();
            assertThat(checklists.rename(second, new RenameChecklistItemRequest("Second")).ordinal()).isEqualTo(1);
            assertThat(checklists.rename(third, new RenameChecklistItemRequest("Third")).ordinal()).isEqualTo(2);
        });
    }

    @Test
    void renameChangesTheLabelWithoutTouchingDoneOrOrdinal() {
        UUID tenant = fixture.createTenant("checklist-rename");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);
            UUID itemId = checklists.add(taskId, new AddChecklistItemRequest("Origonal typo"));

            var renamed = checklists.rename(itemId, new RenameChecklistItemRequest("Original"));

            assertThat(renamed.label()).isEqualTo("Original");
            assertThat(renamed.done()).isFalse();
            assertThat(renamed.ordinal()).isZero();
        });
    }

    @Test
    void reorderChangesTheOrdinalOnly() {
        UUID tenant = fixture.createTenant("checklist-reorder");
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);
            UUID itemId = checklists.add(taskId, new AddChecklistItemRequest("Move me"));

            var reordered = checklists.reorder(itemId, new ReorderChecklistItemRequest(5));

            assertThat(reordered.ordinal()).isEqualTo(5);
            assertThat(reordered.label()).isEqualTo("Move me");
        });
    }

    /**
     * Structural edits are gated task.manage; toggling is gated task.complete
     * (design spec 5.2's own distinction, restated in the brief). An actor
     * holding only task.complete must be refused renaming outright, not
     * silently allowed through a shared endpoint.
     */
    @Test
    void renamingWithOnlyTaskCompleteIsRefused() {
        UUID tenant = fixture.createTenant("checklist-gate-split");
        var narrowActor = new UUID[1];
        var itemId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID taskId = adHocTask(tenant);
            itemId[0] = checklists.add(taskId, new AddChecklistItemRequest("Untouchable by task.complete alone"));

            narrowActor[0] = fixture.createUser(tenant, "complete-only@example.com");
            grant(narrowActor[0], Map.of(
                    PermissionKeys.TASK_COMPLETE, Scope.ALL,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor[0], () ->
                checklists.rename(itemId[0], new RenameChecklistItemRequest("Renamed anyway"))))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The narrowest scope task.complete allows. The actor is both the task's
     * assignee (TaskDescriptor/TaskChecklistItemDescriptor's own ASSIGNED axis)
     * AND a case_participant with an ASSIGNED-qualifying relationship
     * (CaseDescriptor/MilestoneDescriptor's own ASSIGNED axis, resolved via
     * case_participant, never task.assignee_id) -- ChecklistService's
     * checkWriteScope resolves Milestone and Case under the SAME task.complete
     * permission, inheriting TaskService.changeStatus's identical chain, so
     * both relationships must hold for an ASSIGNED-only holder to reach all
     * the way through to StageWriteScopeGuard. See this class's own note in
     * the task-22 report.
     */
    @Test
    void anAssigneeWhoIsAlsoACaseParticipantMayToggleAtAssignedScope() {
        UUID tenant = fixture.createTenant("checklist-assigned-scope");
        var assignee = new UUID[1];
        var itemId = new UUID[1];
        fixture.runAs(tenant, () -> {
            assignee[0] = fixture.createUser(tenant, "assignee@example.com");
            grant(assignee[0], Map.of(
                    PermissionKeys.TASK_COMPLETE, Scope.ASSIGNED,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID caseId = simpleCase(tenant);
            journey.addParticipant(tenant, caseId, assignee[0],
                    RelationshipType.ASSIGNEE, ParticipantStatus.ACTIVE);
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Assigned task", null,
                    TaskPriority.LOW, assignee[0], null)).id();
            itemId[0] = checklists.add(taskId, new AddChecklistItemRequest("Do the thing"));
        });

        var toggled = new ChecklistItemView[1];
        fixture.runAsUser(tenant, assignee[0], () -> toggled[0] = checklists.toggle(itemId[0]));

        assertThat(toggled[0].done()).isTrue();
    }

    /**
     * TaskChecklistItemDescriptor resolves DEPARTMENT/TEAM one hop further than
     * TaskDescriptor's own (item -> task -> case). A DEPARTMENT-scoped
     * task.manage holder in a DIFFERENT department than the case owning the
     * item's task must get a 404, never a 403 -- CLAUDE.md's "out-of-scope
     * records return 404, never 403," proven against the item read itself
     * rather than only the parent task's.
     */
    @Test
    void anItemInAnotherDepartmentIsA404ForADepartmentScopedActor() {
        UUID tenant = fixture.createTenant("checklist-department-scope");
        var narrowActor = new UUID[1];
        var itemId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID owningDepartment = fixture.createDepartment(tenant, "Owning Department");
            UUID otherDepartment = fixture.createDepartment(tenant, "Other Department");
            narrowActor[0] = fixture.createUserInDepartment(tenant, "other-dept@example.com", otherDepartment);
            grant(narrowActor[0], Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

            UUID customerId = fixture.createCustomer(
                    tenant, "Acme " + Uuid7.generate(), null, owningDepartment, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID milestoneId = firstMilestone(caseId);
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Owning department's task", null,
                    TaskPriority.LOW, null, null)).id();
            itemId[0] = checklists.add(taskId, new AddChecklistItemRequest("Not yours to rename"));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor[0], () ->
                checklists.rename(itemId[0], new RenameChecklistItemRequest("Reassigned"))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * StageWriteScopeGuard gates every task mutation (design spec 6.3,
     * CLAUDE.md's "what sub-project 3 inherits") -- a checklist item mutation
     * is a task mutation in this broader sense. A DEPARTMENT-scoped task.manage
     * holder, fellow department member but not the case/milestone owner, must
     * still be refused inside an OWNER_ONLY stage -- shape borrowed from
     * journey.WriteScopeTest.allScopeIsStillRefusedInAnOwnerOnlyStage and
     * TaskServiceTest's own equivalent.
     */
    @Test
    void stageWriteScopeGuardRefusesRenameInAnOwnerOnlyStageForANonOwner() {
        UUID tenant = fixture.createTenant("checklist-write-scope");
        var narrowActor = new UUID[1];
        var itemId = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Shared Department");
            narrowActor[0] = fixture.createUserInDepartment(tenant, "narrow@example.com", department);
            grant(narrowActor[0], Map.of(
                    PermissionKeys.TASK_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.WORKFLOW_VIEW, Scope.ALL));

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
            UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                    List.of(restrictedStage), List.of(), 0L));

            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, journey.templateOf(versionId), "Fixture Case " + Uuid7.generate(), Map.of())).id();
            UUID restrictedMilestoneId = cases.roadmap(caseId).stages().get(0).milestones().get(0).id();

            var taskId = new UUID[1];
            fixture.runAsUser(tenant, caseOwner, () -> taskId[0] = tasks.create(caseId,
                    new CreateTaskRequest(restrictedMilestoneId, null, "Restricted task", null,
                            TaskPriority.MEDIUM, null, null)).id());
            fixture.runAsUser(tenant, caseOwner, () ->
                    itemId[0] = checklists.add(taskId[0], new AddChecklistItemRequest("Owner-only item")));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor[0], () ->
                checklists.rename(itemId[0], new RenameChecklistItemRequest("Renamed by a non-owner"))))
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

    /** An ad-hoc task (no requirement) on a fresh case's first milestone. */
    private UUID adHocTask(UUID tenant) {
        UUID caseId = simpleCase(tenant);
        UUID milestoneId = firstMilestone(caseId);
        return tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Ad-hoc", null, TaskPriority.LOW, null, null)).id();
    }
}
