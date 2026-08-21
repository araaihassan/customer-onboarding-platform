package co.ara.onboarding.workflow;

import co.ara.onboarding.authz.PermissionKeys;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Thin: binds path variables and delegates to {@link WorkflowService} /
 * {@link PublishService}. No authorization logic here -- that is the gate's job, and
 * ModuleBoundaryTest stops this class reaching a repository.
 *
 * Nine endpoints live here rather than under /admin/workflows: workflow.view belongs
 * to every operational role that must read the definition its case is frozen on, so
 * gating by path would either exclude them or make /admin mean nothing. Admin-ness is
 * a permission a role holds, not a URL prefix; the frontend route stays under admin/.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/workflows")
public class WorkflowController {

    private static final String FORBIDDEN = "Caller holds no sufficient workflow.view / workflow.manage grant";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final WorkflowService workflows;
    private final PublishService publishService;

    public WorkflowController(WorkflowService workflows, PublishService publishService) {
        this.workflows = workflows;
        this.publishService = publishService;
    }

    @GetMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Templates the caller may view"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<WorkflowTemplateView> list() {
        return workflows.listTemplates();
    }

    @PostMapping
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Template created"),
            @ApiResponse(responseCode = "400", description = "A blank name failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WorkflowTemplateView create(@Valid @RequestBody CreateTemplateRequest r) {
        return workflows.createTemplate(r.name(), r.description());
    }

    @GetMapping("/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The template"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WorkflowTemplateView get(@PathVariable UUID id) {
        return workflows.getTemplate(id);
    }

    @PostMapping("/{id}/deactivate")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deactivated"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void deactivate(@PathVariable UUID id) {
        workflows.deactivateTemplate(id);
    }

    @GetMapping("/{id}/versions/{vid}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The version's whole graph"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WorkflowDefinitionView definition(@PathVariable UUID vid) {
        return workflows.getDefinition(vid);
    }

    @PostMapping("/{id}/versions")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft created, empty or copied from the current published version"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The template already has an open draft",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WorkflowDefinitionView newDraft(@PathVariable UUID id) {
        UUID versionId = workflows.createDraft(id);
        // Built under WORKFLOW_MANAGE -- the permission createDraft's own gate already
        // checked -- not re-read via WORKFLOW_VIEW, for the same manage-without-view
        // reason WorkflowService.replaceDraft's own return statement avoids: createDraft
        // returns a bare UUID, and a role holding manage without view would otherwise
        // pass the gate above, write the draft, and 403 on this method's own return value.
        return workflows.getDefinitionAs(versionId, PermissionKeys.WORKFLOW_MANAGE);
    }

    @PutMapping("/{id}/versions/{vid}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved draft"),
            @ApiResponse(responseCode = "400", description = "A frozen version, or an unknown reference key",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Someone else saved this draft first (stale lockVersion)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public WorkflowDefinitionView replace(@PathVariable UUID vid, @Valid @RequestBody WorkflowDefinitionRequest r) {
        return workflows.replaceDraft(vid, r);
    }

    @PostMapping("/{id}/versions/{vid}/publish")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Published"),
            @ApiResponse(responseCode = "400", description = "The version is not a draft",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Every publish-time problem found, so the builder can list them all",
                    content = @Content(schema = @Schema(implementation = WorkflowExceptionHandler.ProblemList.class)))
    })
    public WorkflowDefinitionView publish(@PathVariable UUID vid) {
        return publishService.publish(vid);
    }

    @PostMapping("/{id}/versions/{vid}/discard")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Discarded"),
            @ApiResponse(responseCode = "400", description = "The version is not a draft",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void discard(@PathVariable UUID vid) {
        workflows.discardDraft(vid);
    }
}
