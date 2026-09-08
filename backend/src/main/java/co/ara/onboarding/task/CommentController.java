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
 * Internal comments on a task or a journey (design spec §7). Thin: binds
 * path/query parameters and delegates to {@link CommentService} -- no
 * authorization logic here, matching {@code journey.MilestoneController}'s
 * own doc comment. Reading and posting are both nested under the case
 * ({@code /cases/{caseId}/comments}) because {@code case_id} is what
 * {@link CommentService} resolves and checks first (see its own doc
 * comment); editing is not, since an edit names the comment directly and has
 * no other case it could sensibly be nested under.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class CommentController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant for this action";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final CommentService comments;

    public CommentController(CommentService comments) {
        this.comments = comments;
    }

    @GetMapping("/cases/{caseId}/comments")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every comment on this resource, gated case.view -- "
                    + "see CommentService's own doc comment for why there is no bespoke comment.view"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CommentView> forResource(@PathVariable UUID caseId,
                                         @RequestParam CommentResourceType resourceType,
                                         @RequestParam UUID resourceId) {
        return comments.forResource(caseId, resourceType, resourceId);
    }

    @PostMapping("/cases/{caseId}/comments")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Posted"),
            @ApiResponse(responseCode = "400", description = "A blank body failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The case is absent/out of scope, or resourceId does "
                    + "not belong to it",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CommentView create(@PathVariable UUID caseId, @Valid @RequestBody CreateCommentRequest request) {
        return comments.create(caseId, request);
    }

    /** Editing is author-only, enforced inside CommentService itself -- see its own doc comment. */
    @PutMapping("/comments/{commentId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved -- editedAt now set"),
            @ApiResponse(responseCode = "400", description = "A blank body failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = "Caller holds no comment.create, or is not this "
                    + "comment's own author",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CommentView update(@PathVariable UUID commentId, @Valid @RequestBody UpdateCommentRequest request) {
        return comments.update(commentId, request);
    }
}
