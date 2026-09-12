package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.DecidePlanRequest;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Sub-project 3A, Task 27.5 (inserted plan amendment -- see
 * {@code .superpowers/sdd/2026-09-08-programmes-and-customer-plans/task-27.5-brief.md}
 * and the plan itself for the finding this closes). Task 27 wired only {@link
 * #diff}; {@code issue}/{@code get}/{@code decide} (Tasks 24-25) had been
 * proven at the service layer only, with no REST endpoint at all, since each
 * task's own brief scoped "wire a controller" out. This class now wires the
 * whole of {@link PlanRevisionService} -- none of its four methods' own
 * signatures or internal logic changed by this task, only how they are
 * reached over HTTP.
 *
 * Remounted at {@code /api/t/{tenantSlug}/cases/{caseId}/plan-revisions},
 * following {@link ApprovalController}'s own case-nested convention rather
 * than {@code diff}'s original flat {@code /plan-revisions/{revisionId}/diff}
 * -- no frontend consumed the old shape yet, so this was free to change.
 * {@code caseId} in the path is for URL consistency only: {@link #get}/
 * {@link #decide}/{@link #diff} resolve their own id (a {@code revisionId})
 * through {@link PlanRevisionService}'s existing {@link
 * co.ara.onboarding.authz.AuthorizedQuery} calls and never cross-check it
 * against the path's {@code caseId} -- that is out of scope for this
 * bridging task (see the brief).
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/cases/{caseId}/plan-revisions")
public class PlanRevisionController {

    private static final String FORBIDDEN_ISSUE = "Caller holds no sufficient plan.issue grant";
    private static final String FORBIDDEN_DECIDE = "Caller holds no sufficient plan.approve_schedule grant";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope";
    private static final String NOT_FOUND_DIFF =
            "Absent, out of the caller's scope, or not from the same case as the id being diffed against";

    private final PlanRevisionService revisions;

    public PlanRevisionController(PlanRevisionService revisions) { this.revisions = revisions; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Issued -- supersedes any outstanding revision for this case"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_ISSUE,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The case's plan shape has not yet been approved (gate 1 -> gate 2 ordering)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PlanRevisionView issue(@PathVariable UUID caseId, @RequestBody IssueRevisionRequest request) {
        return revisions.issue(caseId, request);
    }

    @GetMapping
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every schedule revision ever issued for this case, newest first"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_ISSUE,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<PlanRevisionView> listForCase(@PathVariable UUID caseId) {
        return revisions.listForCase(caseId);
    }

    @GetMapping("/{revisionId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The revision and its frozen items"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_ISSUE,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PlanRevisionView get(@PathVariable UUID caseId, @PathVariable UUID revisionId) {
        return revisions.get(revisionId);
    }

    @PostMapping("/{revisionId}/decision")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decision recorded -- approving a case's first-ever revision releases its hold (QA Q22/Q23)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_DECIDE,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The revision has no outstanding decision to decide -- a decision is one-shot",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PlanRevisionView decide(@PathVariable UUID caseId, @PathVariable UUID revisionId,
                                    @Valid @RequestBody DecidePlanRequest request) {
        return revisions.decide(revisionId, request);
    }

    @GetMapping("/{revisionId}/diff")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every milestone's change between the two revisions, computed server-side"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN_ISSUE,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND_DIFF,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PlanRevisionDiffView diff(@PathVariable UUID caseId, @PathVariable UUID revisionId,
                                      @RequestParam UUID against) {
        return revisions.diff(revisionId, against);
    }
}
