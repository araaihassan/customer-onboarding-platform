package co.ara.onboarding.task;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Tasks and their checklist items (design spec §7). Thin: binds path/query
 * parameters and delegates to {@link TaskService} / {@link ChecklistService}
 * -- no authorization logic here, that is {@code @RequirePermission} and
 * {@link co.ara.onboarding.authz.AuthorizedQuery}'s job, the same shape
 * {@code journey.MilestoneController}'s own doc comment describes.
 *
 * <p>Checklist endpoints live here rather than in a separate
 * {@code ChecklistController} -- the plan's own file list for this task names
 * only {@code TaskController}/{@code CommentController}, and a checklist item
 * has no existence independent of the task it belongs to, the same shape
 * requirements share {@code MilestoneController} rather than getting their
 * own class.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class TaskController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant, or write_scope refused this stage";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final TaskService tasks;
    private final ChecklistService checklists;

    public TaskController(TaskService tasks, ChecklistService checklists) {
        this.tasks = tasks;
        this.checklists = checklists;
    }

    /** Every task on a case, for the Tasks tab -- grouping by milestone is the frontend's own job (Task 27). */
    @GetMapping("/cases/{caseId}/tasks")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every task on the case"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<TaskView> forCase(@PathVariable UUID caseId) {
        return tasks.forCase(caseId);
    }

    @PostMapping("/cases/{caseId}/tasks")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created -- an ad-hoc task, no requirement attached"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The case or the milestone is absent, out of scope, "
                    + "or the milestone does not belong to this case",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TaskView create(@PathVariable UUID caseId, @Valid @RequestBody CreateTaskRequest request) {
        return tasks.create(caseId, request);
    }

    /**
     * "My work" (spec §8.2) -- a cross-case board of the CALLING actor's own
     * assignments. {@code assignee=me} is the only supported value: the spec
     * defines no cross-user variant, so anything else is refused rather than
     * silently ignored. See {@link TaskService#myWork} for the bucket mapping
     * and why assigneeId is pinned server-side regardless of scope.
     *
     * <p>Mapped ahead of {@link #get}: Spring resolves {@code /tasks} against
     * this method and {@code /tasks/{taskId}} against that one as distinct
     * mappings, so there is no ordering ambiguity between them.
     */
    @GetMapping("/tasks")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's own tasks in the requested bucket "
                    + "(or all four non-CANCELLED buckets, if none was given)"),
            @ApiResponse(responseCode = "400", description = "assignee was not \"me\", or bucket named none of "
                    + "do_now/in_progress/waiting/done_this_week",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<TaskView> myWork(@RequestParam(defaultValue = "me") String assignee,
                                 @RequestParam(required = false) String bucket) {
        if (!"me".equals(assignee)) {
            throw new IllegalArgumentException("assignee must be \"me\" -- there is no cross-user variant");
        }
        return tasks.myWork(bucket);
    }

    @GetMapping("/tasks/{taskId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The task"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TaskView get(@PathVariable UUID taskId) {
        return tasks.get(taskId);
    }

    /** A full replace of everything about a task except its lifecycle and its requirementId -- see UpdateTaskRequest's own doc comment. */
    @PutMapping("/tasks/{taskId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TaskView update(@PathVariable UUID taskId, @Valid @RequestBody UpdateTaskRequest request) {
        return tasks.update(taskId, request);
    }

    /** Status transitions, gated task.complete -- see TaskService.changeStatus's own doc comment for the transition matrix. */
    @PostMapping("/tasks/{taskId}/status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Transitioned"),
            @ApiResponse(responseCode = "400", description = "A CANCELLED transition with a blank reason",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The current status has no edge to the requested one",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public TaskView changeStatus(@PathVariable UUID taskId, @Valid @RequestBody TaskStatusRequest request) {
        return tasks.changeStatus(taskId, request);
    }

    /**
     * Every checklist item on a task, ordered -- closes a plan gap found building the Tasks tab
     * (Task 27): nothing previously exposed a way to read a task's checklist except as a side
     * effect of mutating one item, so a fresh page load had no way to show items added in an
     * earlier session. Gated task.view (see {@link ChecklistService#list}'s own doc comment), not
     * task.manage/task.complete -- a view-only holder must still see the checklist.
     */
    @GetMapping("/tasks/{taskId}/checklist")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every checklist item on the task, ordered"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<ChecklistItemView> checklist(@PathVariable UUID taskId) {
        return checklists.list(taskId);
    }

    /**
     * Adds one checklist line. Returns the new item's id rather than a full
     * {@link ChecklistItemView} -- {@link ChecklistService#add} (Task 22,
     * untouched by this task) returns only a {@link UUID}, and it has no bare
     * "read one item" method for this controller to compose a view from
     * without either a second, unrelated write or a new repository read this
     * module's own coverage rule (AuthorizationCoverageTest) would then have
     * to account for. The id is what a client needs to rename/reorder/toggle
     * the item next.
     */
    @PostMapping("/tasks/{taskId}/checklist")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Added -- the new item's id"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public UUID addChecklistItem(@PathVariable UUID taskId, @Valid @RequestBody AddChecklistItemRequest request) {
        return checklists.add(taskId, request);
    }

    /**
     * The one endpoint the spec exposes over {@link ChecklistService}'s three
     * separately-gated methods -- see {@link UpdateChecklistItemRequest}'s own
     * doc comment for why the request is shaped the way it is. Each non-null
     * field triggers its own call, each under its own permission; a request
     * naming fields under permissions the caller does not fully hold applies
     * whichever it is authorized for and then throws on the first one it is
     * not -- there is no wrapping transaction across the three service calls,
     * each of which commits (or fails) independently, so this is a
     * best-effort composite, not an atomic one. A caller changing exactly one
     * property at a time (the common case) never observes this.
     */
    @PutMapping("/checklist/{itemId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The item after every requested change was applied"),
            @ApiResponse(responseCode = "400", description = "Validation failed, or every field was null",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ChecklistItemView updateChecklistItem(@PathVariable UUID itemId,
                                                 @Valid @RequestBody UpdateChecklistItemRequest request) {
        if (request.label() == null && request.ordinal() == null && request.toggleDone() == null) {
            throw new IllegalArgumentException(
                    "At least one of label, ordinal or toggleDone must be given");
        }

        ChecklistItemView view = null;
        if (request.label() != null) {
            view = checklists.rename(itemId, new RenameChecklistItemRequest(request.label()));
        }
        if (request.ordinal() != null) {
            view = checklists.reorder(itemId, new ReorderChecklistItemRequest(request.ordinal()));
        }
        if (Boolean.TRUE.equals(request.toggleDone())) {
            view = checklists.toggle(itemId);
        }
        return view;
    }
}
