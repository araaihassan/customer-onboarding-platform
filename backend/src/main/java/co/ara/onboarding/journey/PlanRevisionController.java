package co.ara.onboarding.journey;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Sub-project 3A, Task 27: the first REST surface for {@link PlanRevisionService}.
 * {@code issue}/{@code get}/{@code decide} (Tasks 24-25) were built and proven
 * at the service layer only -- no controller ever wired them, despite the
 * plan's own module-structure listing naming this file at that point. This
 * class wires ONLY {@link #diff}, this task's own deliverable; wiring {@code
 * issue}/{@code get}/{@code decide} themselves is a carried-forward gap this
 * task does not attempt (see this task's own report and CLAUDE.md's plan
 * deviations note) -- Task 28's frontend hooks for those three will need it.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/plan-revisions")
public class PlanRevisionController {

    private static final String FORBIDDEN = "Caller holds no sufficient plan.issue grant";
    private static final String NOT_FOUND =
            "Absent, out of the caller's scope, or not from the same case as the id being diffed against";

    private final PlanRevisionService revisions;

    public PlanRevisionController(PlanRevisionService revisions) { this.revisions = revisions; }

    @GetMapping("/{revisionId}/diff")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every milestone's change between the two revisions, computed server-side"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public PlanRevisionDiffView diff(@PathVariable UUID revisionId, @RequestParam UUID against) {
        return revisions.diff(revisionId, against);
    }
}
