package co.ara.onboarding.task;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
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
import co.ara.onboarding.journey.RequirementService;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

/**
 * Create, read, full-replace update, and status transitions for ad-hoc and
 * (later, Task 19) requirement-instantiated tasks. Deliberately NOT a caller
 * of {@code CaseEngine.reconcile}, and takes no case-level lock itself: a
 * task carries no progress of its own, and {@code changeStatus} completing a
 * requirement-linked task routes through the already-gated
 * {@code RequirementService.satisfy} rather than reconciling directly.
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
    private final RequirementService requirements;
    private final AuditRecorder audit;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    /**
     * The finite status graph drawn in the design spec's §5.1:
     *
     * <pre>
     *   PENDING ──► IN_PROGRESS ──► COMPLETED
     *      │             │
     *      └──► WAITING ─┘
     *   any open state ──► CANCELLED
     * </pre>
     *
     * The diagram draws PENDING→IN_PROGRESS→COMPLETED as the canonical path
     * and PENDING→WAITING→IN_PROGRESS as the one detour through it, but its
     * own prose generalises the CANCELLED edge to "any open state" rather
     * than drawing three separate arrows -- and the brief's own required
     * tests (completing a freshly-created, still-PENDING task in one call,
     * with no prior transition to IN_PROGRESS) only make sense if COMPLETED
     * gets the same "any open state" treatment as CANCELLED: both are the
     * two ways a task's open lifecycle can end, reachable directly from
     * PENDING, IN_PROGRESS or WAITING alike. What the diagram's specific
     * arrows fix is movement AMONG the three open states themselves --
     * PENDING→IN_PROGRESS, PENDING→WAITING, WAITING→IN_PROGRESS are the only
     * ones drawn, so IN_PROGRESS can never move back to PENDING or sideways
     * to WAITING, and WAITING can never move back to PENDING. COMPLETED and
     * CANCELLED map to an empty set -- both are terminal from this method's
     * own perspective; the only route back out of COMPLETED is a milestone
     * reopen (TaskLifecycle.reopenForMilestone), a completely separate
     * mechanism not reachable through changeStatus.
     */
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = Map.of(
            TaskStatus.PENDING, Set.of(TaskStatus.IN_PROGRESS, TaskStatus.WAITING,
                    TaskStatus.COMPLETED, TaskStatus.CANCELLED),
            TaskStatus.IN_PROGRESS, Set.of(TaskStatus.COMPLETED, TaskStatus.CANCELLED),
            TaskStatus.WAITING, Set.of(TaskStatus.IN_PROGRESS, TaskStatus.COMPLETED, TaskStatus.CANCELLED),
            TaskStatus.COMPLETED, Set.of(),
            TaskStatus.CANCELLED, Set.of());

    public TaskService(TaskRepository tasks, MilestoneRepository milestones, CaseRepository cases,
                       RequirementRepository requirementRepository,
                       MilestoneDefinitionRepository milestoneDefinitions, StageRepository stages,
                       AppUserRepository users, AuthorizedQuery authorizedQuery,
                       StageWriteScopeGuard writeScope, RequirementService requirements,
                       AuditRecorder audit, AuthContextProvider contextProvider, Clock clock) {
        this.tasks = tasks;
        this.milestones = milestones;
        this.cases = cases;
        this.requirementRepository = requirementRepository;
        this.milestoneDefinitions = milestoneDefinitions;
        this.stages = stages;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.writeScope = writeScope;
        this.requirements = requirements;
        this.audit = audit;
        this.contextProvider = contextProvider;
        this.clock = clock;
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

    /**
     * Transitions a task's status through the finite matrix TRANSITIONS
     * encodes (see its own javadoc). Gated the same way every other write
     * here is: task.complete resolves the task and (via the same permission)
     * its case and milestone, then {@link StageWriteScopeGuard} narrows
     * further against the milestone's own stage, exactly as update() does.
     *
     * Completing a requirement-linked task calls the EXISTING
     * RequirementService.satisfy(requirementId, taskId, "task") -- already
     * gated on milestone.complete, already taking CaseRepository.lockById's
     * row lock via CaseEngine, already reconciling and auditing. That is the
     * ONLY path from here to a case mutation; this method never calls
     * CaseEngine itself, so sub-project 2's invariant 4 (every runtime
     * mutation goes through CaseEngine.reconcile, under that lock, and
     * nothing else calls it) holds by construction rather than by
     * discipline. An ad-hoc task (requirementId == null) calls nothing on
     * RequirementService at all: progress is derived from requirements
     * alone, so there is nothing for the engine to recompute -- if a later
     * sub-project ever makes tasks count toward progress, this is the line
     * that must change.
     *
     * Design spec §5.2's authorization consequence, stated rather than
     * discovered: because RequirementService.satisfy carries its own
     * @RequirePermission(MILESTONE_COMPLETE) and that gate applies across the
     * bean boundary, completing a requirement-linked task requires BOTH
     * task.complete (this method's own gate) AND milestone.complete. An actor
     * holding only the former is refused by satisfy's own aspect with
     * AccessDeniedException -- not a bug, and not something this method
     * catches or works around.
     *
     * A transition into CANCELLED (Task 18) is the other terminal edge and is
     * handled entirely locally: a blank reason is refused in Java --
     * task_cancel_reason_ck is the backstop, not the error message -- and
     * cancelling calls neither {@code RequirementService.satisfy} nor {@code
     * RequirementService.waive}. A requirement-linked task's requirement is
     * left completely untouched (open, not satisfied, not waived): waiving is
     * its own gated, reasoned action, and routing around it here would be a
     * silent waiver under this method's weaker task.complete gate. The
     * cancellation gets its own audit action, TASK_CANCELLED, recorded
     * additionally alongside (never instead of) TASK_STATUS_CHANGED -- same
     * shape as CONTACT_DEACTIVATED next to CONTACT_UPDATED.
     */
    @RequirePermission(PermissionKeys.TASK_COMPLETE)
    @Transactional
    public TaskView changeStatus(UUID taskId, TaskStatusRequest request) {
        Task t = authorizedQuery.getById(tasks, Task.class, PermissionKeys.TASK_COMPLETE, taskId);
        guardTransition(t.getStatus(), request.status());

        if (request.status() == TaskStatus.CANCELLED
                && (request.reason() == null || request.reason().isBlank())) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }

        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.TASK_COMPLETE, t.getMilestoneId());
        Case c = authorizedQuery.getById(cases, Case.class, PermissionKeys.TASK_COMPLETE, m.getCaseId());
        writeScope.check(c, m, stageOf(m));

        TaskStatus previous = t.getStatus();
        t.setStatus(request.status());
        if (request.status() == TaskStatus.COMPLETED) {
            t.setCompletedAt(Instant.now(clock));
            t.setCompletedBy(contextProvider.principal().userId());
        } else if (request.status() == TaskStatus.CANCELLED) {
            t.setCancelledAt(Instant.now(clock));
            t.setCancellationReason(request.reason());
        }
        tasks.save(t);

        // Cause before effects (AuditRecorder): this status-change event must
        // be written before RequirementService.satisfy below, whose own
        // audit entry and reconcile record what THIS transition triggered --
        // never the other way round.
        audit.record(AuditActions.TASK_STATUS_CHANGED, "onboarding_case", c.getId(),
                "Task \"" + t.getTitle() + "\" moved from " + previous + " to " + request.status(),
                Map.of("taskId", t.getId().toString(), "milestoneId", m.getId().toString()));

        if (request.status() == TaskStatus.CANCELLED) {
            audit.record(AuditActions.TASK_CANCELLED, "onboarding_case", c.getId(),
                    "Cancelled task \"" + t.getTitle() + "\": " + request.reason(),
                    Map.of("taskId", t.getId().toString(), "milestoneId", m.getId().toString()));
        }

        if (request.status() == TaskStatus.COMPLETED && t.getRequirementId() != null) {
            requirements.satisfy(t.getRequirementId(), t.getId(), "task");
        }
        return toView(t);
    }

    /**
     * "My work" (design spec §7/§8.2): a cross-case board scoped to the
     * CALLING actor's own assignments, added here rather than left to
     * {@code TaskController} because it is a read of tenant business data
     * (CLAUDE.md's Global Constraints -- every such read goes through
     * {@link AuthorizedQuery}) and needs its own {@code @RequirePermission}
     * gate, neither of which a controller may carry. This method was not in
     * Task 24's own file list (which named only the controllers, the
     * exception handler and the generated client) -- the spec's endpoint
     * needs a service-layer query no earlier task added, so this is a plan
     * gap closed here rather than deferred, the same shape CLAUDE.md's
     * "Plan deviations" section describes.
     *
     * assigneeId is pinned to the calling principal regardless of whatever
     * wider record-scope the actor's own task.view grant would otherwise
     * resolve -- an ALL-scoped administrator's own work board must show only
     * THEIR assignments, not the whole tenant's. The spec's URL is literally
     * {@code assignee=me}; there is no cross-user variant, so {@code
     * TaskController} is the only caller and always means the current
     * principal -- there is nothing here for a caller-supplied user id to
     * escalate through in the first place.
     *
     * bucket partitions spec §8.2's four non-CANCELLED columns ("do_now",
     * "in_progress", "waiting", "done_this_week"); a value outside those four
     * is refused as {@link IllegalArgumentException} (400) rather than
     * silently returning nothing, and a null bucket returns the union of all
     * four -- still excluding CANCELLED, "which is what makes the headline
     * true rather than decorative" (spec §8.2). "Done this week" is completed
     * status BUT completedAt within the trailing 7 days, matching the
     * column's own name; it is deliberately not a rolling requirement/task
     * completion count, since progress is derived from requirements alone.
     */
    @RequirePermission(PermissionKeys.TASK_VIEW)
    @Transactional(readOnly = true)
    public List<TaskView> myWork(String bucket) {
        UUID self = contextProvider.principal().userId();
        Specification<Task> mine = (root, query, cb) -> cb.equal(root.get("assigneeId"), self);
        Specification<Task> scoped = mine.and(bucketFilter(bucket));
        return authorizedQuery.findAll(tasks, Task.class, PermissionKeys.TASK_VIEW, scoped, Pageable.unpaged())
                .getContent().stream().map(this::toView).toList();
    }

    private Specification<Task> bucketFilter(String bucket) {
        Instant weekAgo = Instant.now(clock).minus(Duration.ofDays(7));
        if (bucket == null) {
            return (root, query, cb) -> cb.or(
                    cb.equal(root.get("status"), TaskStatus.PENDING),
                    cb.equal(root.get("status"), TaskStatus.IN_PROGRESS),
                    cb.equal(root.get("status"), TaskStatus.WAITING),
                    cb.and(cb.equal(root.get("status"), TaskStatus.COMPLETED),
                            cb.greaterThanOrEqualTo(root.get("completedAt"), weekAgo)));
        }
        return switch (bucket) {
            case "do_now" -> (root, query, cb) -> cb.equal(root.get("status"), TaskStatus.PENDING);
            case "in_progress" -> (root, query, cb) -> cb.equal(root.get("status"), TaskStatus.IN_PROGRESS);
            case "waiting" -> (root, query, cb) -> cb.equal(root.get("status"), TaskStatus.WAITING);
            case "done_this_week" -> (root, query, cb) -> cb.and(
                    cb.equal(root.get("status"), TaskStatus.COMPLETED),
                    cb.greaterThanOrEqualTo(root.get("completedAt"), weekAgo));
            default -> throw new IllegalArgumentException("Unknown bucket: " + bucket);
        };
    }

    private void guardTransition(TaskStatus from, TaskStatus to) {
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalTaskTransitionException(from, to);
        }
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
