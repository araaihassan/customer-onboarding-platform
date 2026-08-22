package co.ara.onboarding.journey;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Two decide endpoints, not one -- {@code @RequirePermission} is static, so a
 * single path could not carry {@code approval.decide} for a stage exit and
 * {@code milestone.force_approve} for a forcing without hiding an authorization
 * decision inside the method body where no coverage test can see it. Each refuses
 * the other's kind of approval (ApprovalKindMismatchException, mapped to 409).
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/cases/{caseId}")
public class ApprovalController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant for this decide path";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) { this.approvals = approvals; }

    @GetMapping("/approvals")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every approval recorded against the case, of both kinds"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<ApprovalView> list(@PathVariable UUID caseId) {
        return approvals.listForCase(caseId);
    }

    @PostMapping("/stage-approvals/{aid}/decide")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided -- approving re-reconciles the case"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Not a STAGE_EXIT approval, or already decided",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ApprovalView decideStageExit(@PathVariable UUID caseId, @PathVariable UUID aid,
                                        @RequestBody DecideRequest request) {
        return approvals.decideStageExit(aid, request.approve(), request.note());
    }

    @PostMapping("/force-requests/{aid}/decide")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decided -- an approval marks the milestone DONE as milestone.force_completed"),
            @ApiResponse(responseCode = "403", description = "The decider is the same actor who requested it (Q5)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Not a FORCE_COMPLETE approval, or already decided",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ApprovalView decideForceComplete(@PathVariable UUID caseId, @PathVariable UUID aid,
                                            @RequestBody DecideRequest request) {
        return approvals.decideForceComplete(aid, request.approve(), request.note());
    }
}
