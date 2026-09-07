package co.ara.onboarding.task;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
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
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Create, read and full-replace update for ad-hoc and (later, Task 19)
 * requirement-instantiated tasks. Deliberately NOT a caller of
 * {@code CaseEngine.reconcile}, and takes no case-level lock: a task carries
 * no progress of its own, and completing one is Task 17's job, routed through
 * the already-gated {@code RequirementService.satisfy} rather than through
 * this class.
 *
 * Every id this class receives from a URL or a request body -- caseId,
 * milestoneId, requirementId, assigneeId -- is resolved through
 * {@link AuthorizedQuery} before it is written anywhere, under the same WRITE
 * permission the calling method is itself gated on wherever that permission's
 * own descriptor applies (task.manage for the milestone/case/requirement a
 * mutation touches; user.view for an assignee, the same narrower permission
 * {@code MilestoneService.update} already resolves an owner through, since an
 * AppUser is not scoped by task.manage's own descriptor at all). Fetching a
 * record under a read permission and then writing it is the exact escalation
 * shape {@code RequirementService}'s own doc comment names, and the one that
 * bit sub-project 1 three times.
 *
 * {@code case_id} is always copied from the resolved milestone's own caseId,
 * never taken from a caller-supplied caseId directly -- the two cannot
 * disagree in what gets persisted. They CAN disagree in what a caller
 * *sends*, though (a URL nesting one case with a milestone that actually
 * belongs to another), and {@code create} refuses that mismatch outright as a
 * 404 rather than silently filing the task under the milestone's real case.
 * This is not URL-parameter tidiness: without the check, {@code
 * writeScope.check(c, m, stageOf(m))} would be evaluated against a {@code c}
 * from one case and an {@code m}/stage from a DIFFERENT case, so {@link
 * StageWriteScopeGuard}'s OWNER_ONLY/TEAM/DEPARTMENT branches would test the
 * actor's relationship to the WRONG case's ownership/team/department
 * columns -- a confused-deputy write_scope bypass, the same shape as {@code
 * update} moving a task between milestones (below).
 *
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly now
 * covers co.ara.onboarding.task (added in the same commit as this class) --
 * every repository finder here is reached through AuthorizedQuery.
 */
@Service
public class TaskService {

    private final TaskRepository tasks;
    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final RequirementRepository requirementRepository;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final StageRepository stages;
    private final AppUserRepository users;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;

    public TaskService(TaskRepository tasks, MilestoneRepository milestones, CaseRepository cases,
                       RequirementRepository requirementRepository,
                       MilestoneDefinitionRepository milestoneDefinitions, StageRepository stages,
                       AppUserRepository users, AuthorizedQuery authorizedQuery,
                       StageWriteScopeGuard writeScope) {
        this.tasks = tasks;
        this.milestones = milestones;
        this.cases = cases;
        this.requirementRepository = requirementRepository;
        this.milestoneDefinitions = milestoneDefinitions;
        this.stages = stages;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.writeScope = writeScope;
    }

    /**
     * caseId is the URL's nesting id, resolved here so an out-of-scope case is a
     * 404 before anything about the request body is even considered.
     * milestoneId is the request body's own id (the escalation-shaped one this
     * task's tests exist for): resolved the same way, under the same
     * TASK_MANAGE permission, so a milestone belonging to another tenant --
     * or simply out of this actor's scope -- is indistinguishable from one
     * that does not exist. If the two resolve to different cases, the
     * request is refused as not found rather than silently created under
     * whichever case the milestone actually belongs to -- see the class
     * javadoc for why that is a write_scope-bypass guard, not decoration.
     */
    @RequirePermission(PermissionKeys.TASK_MANAGE)
    @Transactional
    public TaskView create(UUID caseId, CreateTaskRequest request) {
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.TASK_MANAGE, caseId);
        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.TASK_MANAGE, request.milestoneId());
        if (!m.getCaseId().equals(c.getId())) {
            throw new NoSuchElementException("Not found");
        }
        writeScope.check(c, m, stageOf(m));

        Task t = new Task();
        t.setId(Uuid7.generate());
        t.setTenantId(c.getTenantId());
        t.setCaseId(m.getCaseId());     // from the resolved milestone, never from the caseId argument
        t.setMilestoneId(m.getId());
        t.setRequirementId(resolveRequirementId(request.requirementId()));
        t.setTitle(request.title());
        t.setDescription(request.description());
        t.setPriority(request.priority());
        t.setStatus(TaskStatus.PENDING);
        t.setAssigneeId(resolveAssigneeId(request.assigneeId()));
        t.setDueDate(request.dueDate());
        tasks.save(t);
        return toView(t);
    }

    @RequirePermission(PermissionKeys.TASK_VIEW)
    @Transactional(readOnly = true)
    public TaskView get(UUID taskId) {
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_VIEW, taskId);
        return toView(t);
    }

    /**
     * Confirms the case itself is visible first, so an out-of-scope caseId is a
     * 404 rather than a silently empty list -- ApprovalService.listForCase's
     * own reasoning, applied under this class's own gating permission instead
     * of case.view.
     */
    @RequirePermission(PermissionKeys.TASK_VIEW)
    @Transactional(readOnly = true)
    public List<TaskView> forCase(UUID caseId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.TASK_VIEW, caseId);

        Specification<Task> byCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        return authorizedQuery.findAll(tasks, Task.class, PermissionKeys.TASK_VIEW, byCase, Pageable.unpaged())
                .getContent().stream().map(this::toView).toList();
    }

    /**
     * A full replace of everything UpdateTaskRequest accepts, requirementId and
     * status excepted (neither is part of that record -- see its own doc
     * comment). milestoneId may move the task to a different milestone; the
     * write-scope check runs against the TARGET milestone's stage, since that
     * is where the task ends up living, and case_id follows it the same way
     * create's does.
     *
     * When milestoneId actually changes, the task's CURRENT (pre-update)
     * milestone/case/stage is ALSO resolved and checked, independently of the
     * request. Without this, an actor could pull a task out of a restrictive
     * OWNER_ONLY/TEAM stage they have no write access to by naming, as the
     * destination, any permissive milestone/stage they can already reach
     * under TASK_MANAGE's department/team scope -- the destination-only check
     * would pass even though the actor never had write authority over the
     * stage the task is actually being removed from. Same confused-deputy
     * shape the class javadoc names for create's case/milestone mismatch
     * guard. When milestoneId is unchanged, source and destination are the
     * same milestone, so only one resolution/check is performed -- a second,
     * identical one would be redundant, not more correct.
     */
    @RequirePermission(PermissionKeys.TASK_MANAGE)
    @Transactional
    public TaskView update(UUID taskId, UpdateTaskRequest request) {
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_MANAGE, taskId);

        if (!t.getMilestoneId().equals(request.milestoneId())) {
            Milestone source = authorizedQuery.getById(
                    milestones, Milestone.class, PermissionKeys.TASK_MANAGE, t.getMilestoneId());
            Case sourceCase = authorizedQuery.getById(
                    cases, Case.class, PermissionKeys.TASK_MANAGE, source.getCaseId());
            writeScope.check(sourceCase, source, stageOf(source));
        }

        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.TASK_MANAGE, request.milestoneId());
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.TASK_MANAGE, m.getCaseId());
        writeScope.check(c, m, stageOf(m));

        t.setCaseId(m.getCaseId());
        t.setMilestoneId(m.getId());
        t.setTitle(request.title());
        t.setDescription(request.description());
        t.setPriority(request.priority());
        t.setAssigneeId(resolveAssigneeId(request.assigneeId()));
        t.setDueDate(request.dueDate());
        tasks.save(t);
        return toView(t);
    }

    /** Null for an ad-hoc task; otherwise resolved before it is written, same as every other id here. */
    private UUID resolveRequirementId(UUID requirementId) {
        if (requirementId == null) return null;
        return authorizedQuery.getById(
                requirementRepository, Requirement.class, PermissionKeys.TASK_MANAGE, requirementId).getId();
    }

    /**
     * Resolved under USER_VIEW, not TASK_MANAGE: an AppUser has no
     * task-shaped descriptor at all, and MilestoneService.update already
     * establishes this exact narrower-permission precedent for an owner id.
     */
    private UUID resolveAssigneeId(UUID assigneeId) {
        if (assigneeId == null) return null;
        return authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, assigneeId).getId();
    }

    /** The Stage a milestone belongs to, via its definition -- both ALL-only WORKFLOW_VIEW reads. */
    private Stage stageOf(Milestone m) {
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());
        return authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, definition.getStageId());
    }

    private TaskView toView(Task t) {
        return new TaskView(t.getId(), t.getCaseId(), t.getMilestoneId(), t.getRequirementId(),
                t.getTitle(), t.getDescription(), t.getPriority(), t.getStatus(), t.getAssigneeId(),
                t.getDueDate(), t.getCompletedAt(), t.getCompletedBy(),
                t.getCancelledAt(), t.getCancellationReason());
    }
}
