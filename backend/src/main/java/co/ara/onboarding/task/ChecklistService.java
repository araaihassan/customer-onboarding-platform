package co.ara.onboarding.task;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Checklist items on a task -- a private aid to whoever holds the task, never
 * a second progress mechanism (design spec 4.2, 5.2; CLAUDE.md's "what
 * sub-project 3 inherits"). Nothing here calls {@code CaseEngine.reconcile},
 * {@code RequirementService}, or writes {@code Task.status}: progress is
 * derived from requirements alone, and completing a task is a deliberate act
 * through {@code TaskService.changeStatus}, never inferred from "every
 * checklist item is done."
 *
 * Structural edits ({@link #add}, {@link #rename}, {@link #reorder}) are
 * gated {@code task.manage} -- they change what work is described, the same
 * category {@code TaskService.update} sits in. {@link #toggle} is gated
 * {@code task.complete} instead: flipping "done" is doing the work, not
 * editing the task's shape, the same split {@code TaskService.changeStatus}
 * draws between {@code task.manage} and {@code task.complete}.
 *
 * {@link TaskChecklistItem} carries no scoping data of its own -- {@code id},
 * {@code tenantId}, {@code taskId}, {@code label}, {@code done},
 * {@code ordinal} only -- and is not one of the catalog's four record-scoped
 * resource types, so {@code DescriptorRegistry.validate()} never asks for a
 * descriptor here. But {@code AuthorizationPredicateBuilder.forPermission}
 * still dispatches by ENTITY TYPE, independent of the permission's own
 * catalogued resourceType -- exactly how {@code CaseParticipantDescriptor} and
 * {@code CaseAttributeValueDescriptor} already work under
 * {@code case.view}/{@code case.edit} -- so
 * {@code scoping.TaskChecklistItemDescriptor} exists purely to satisfy
 * {@link AuthorizedQuery}'s entity dispatch. Every finder call below --
 * including the single-item lookup by itemId that {@link #rename},
 * {@link #toggle} and {@link #reorder} all start from -- therefore goes
 * through {@link AuthorizedQuery}, never {@link TaskChecklistItemRepository}
 * directly: a raw {@code findById} would still satisfy
 * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}'s
 * literal, name-shaped exclusion list (it names classes, not "any repository
 * lookup with no descriptor"), but it would read across every task in the
 * tenant regardless of the actor's own department/team/assigned scope --
 * exactly the class of bypass that rule exists to catch. Once the item is
 * resolved this way, its parent {@link Task} is INDEPENDENTLY resolved
 * through {@link AuthorizedQuery} too, under the same permission -- that
 * second resolution is what actually supplies the {@link Milestone}/
 * {@link Case}/{@link Stage} chain {@link StageWriteScopeGuard} needs, and
 * doing it through the gate again (rather than trusting the taskId read off
 * the already-scoped item) matches every other chained resolution in this
 * module ({@code TaskService.create}'s milestone -> case, its own
 * {@code stageOf}).
 *
 * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}
 * already covers {@code co.ara.onboarding.task} (added in the same commit as
 * {@code TaskService}, Task 16) -- this class needs no further addition to it.
 */
@Service
public class ChecklistService {

    private final TaskChecklistItemRepository items;
    private final TaskRepository tasks;
    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;

    public ChecklistService(TaskChecklistItemRepository items, TaskRepository tasks,
                            MilestoneRepository milestones, CaseRepository cases,
                            MilestoneDefinitionRepository milestoneDefinitions, StageRepository stages,
                            AuthorizedQuery authorizedQuery, StageWriteScopeGuard writeScope) {
        this.items = items;
        this.tasks = tasks;
        this.milestones = milestones;
        this.cases = cases;
        this.milestoneDefinitions = milestoneDefinitions;
        this.stages = stages;
        this.authorizedQuery = authorizedQuery;
        this.writeScope = writeScope;
    }

    /**
     * taskId is the URL's nesting id, resolved through AuthorizedQuery before
     * anything about the request body is trusted -- an out-of-scope task is a
     * 404 here, same as every other id this module takes from a caller.
     * ordinal is assigned as one past the current maximum on this task, never
     * supplied by the caller -- AddChecklistItemRequest carries only a label.
     */
    @RequirePermission(PermissionKeys.TASK_MANAGE)
    @Transactional
    public UUID add(UUID taskId, AddChecklistItemRequest request) {
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_MANAGE, taskId);
        checkWriteScope(t, PermissionKeys.TASK_MANAGE);

        int nextOrdinal = itemsFor(t.getId(), PermissionKeys.TASK_MANAGE).stream()
                .mapToInt(TaskChecklistItem::getOrdinal).max().orElse(-1) + 1;

        TaskChecklistItem item = new TaskChecklistItem();
        item.setId(Uuid7.generate());
        item.setTenantId(t.getTenantId());
        item.setTaskId(t.getId());
        item.setLabel(request.label());
        item.setDone(false);
        item.setOrdinal(nextOrdinal);
        items.save(item);
        return item.getId();
    }

    /** Structural: relabels the item. Gated task.manage, same as {@link #add} and {@link #reorder}. */
    @RequirePermission(PermissionKeys.TASK_MANAGE)
    @Transactional
    public ChecklistItemView rename(UUID itemId, RenameChecklistItemRequest request) {
        TaskChecklistItem item = authorizedQuery.getById(
                items, TaskChecklistItem.class, PermissionKeys.TASK_MANAGE, itemId);
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_MANAGE, item.getTaskId());
        checkWriteScope(t, PermissionKeys.TASK_MANAGE);

        item.setLabel(request.label());
        items.save(item);
        return toView(item);
    }

    /**
     * Flips {@code done}. Gated task.complete, not task.manage -- ticking an
     * item is doing the work. Deliberately calls nothing else: no
     * {@code CaseEngine.reconcile}, no {@code RequirementService}, no
     * {@code Task.status} write. See the class javadoc and
     * {@code ChecklistTest.checklistItemsNeverEnterProgress} /
     * {@code .everyItemDoneDoesNotCompleteTheTask}.
     */
    @RequirePermission(PermissionKeys.TASK_COMPLETE)
    @Transactional
    public ChecklistItemView toggle(UUID itemId) {
        TaskChecklistItem item = authorizedQuery.getById(
                items, TaskChecklistItem.class, PermissionKeys.TASK_COMPLETE, itemId);
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_COMPLETE, item.getTaskId());
        checkWriteScope(t, PermissionKeys.TASK_COMPLETE);

        item.setDone(!item.isDone());
        items.save(item);
        return toView(item);
    }

    /**
     * Moves one item to a new ordinal. Gated task.manage, same as
     * {@link #add} and {@link #rename} -- reordering is structural.
     */
    @RequirePermission(PermissionKeys.TASK_MANAGE)
    @Transactional
    public ChecklistItemView reorder(UUID itemId, ReorderChecklistItemRequest request) {
        TaskChecklistItem item = authorizedQuery.getById(
                items, TaskChecklistItem.class, PermissionKeys.TASK_MANAGE, itemId);
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_MANAGE, item.getTaskId());
        checkWriteScope(t, PermissionKeys.TASK_MANAGE);

        item.setOrdinal(request.ordinal());
        items.save(item);
        return toView(item);
    }

    /** Every item on a task, ordered, read through AuthorizedQuery under the caller's own gating permission. */
    private List<TaskChecklistItem> itemsFor(UUID taskId, String permission) {
        Specification<TaskChecklistItem> byTask = (root, query, cb) -> cb.equal(root.get("taskId"), taskId);
        return authorizedQuery.findAll(items, TaskChecklistItem.class, permission, byTask, Pageable.unpaged())
                .getContent();
    }

    /**
     * The same milestone -> case -> stage chain TaskService.create/update/
     * changeStatus all resolve, then StageWriteScopeGuard.check -- a stage's
     * write_scope narrows who may write inside it, on top of the record-level
     * scope the permission above already granted, never instead of it.
     */
    private void checkWriteScope(Task t, String permission) {
        Milestone m = authorizedQuery.getById(milestones, Milestone.class, permission, t.getMilestoneId());
        Case c = authorizedQuery.getById(cases, Case.class, permission, m.getCaseId());
        writeScope.check(c, m, stageOf(m));
    }

    /** The Stage a milestone belongs to, via its definition -- both ALL-only WORKFLOW_VIEW reads. */
    private Stage stageOf(Milestone m) {
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());
        return authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, definition.getStageId());
    }

    private ChecklistItemView toView(TaskChecklistItem item) {
        return new ChecklistItemView(item.getId(), item.getTaskId(), item.getLabel(), item.isDone(), item.getOrdinal());
    }
}
