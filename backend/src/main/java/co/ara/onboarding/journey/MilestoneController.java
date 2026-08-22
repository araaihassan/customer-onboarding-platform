package co.ara.onboarding.journey;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * Milestones and their requirements, both nested under a case -- {@code caseId} is
 * bound and unused beyond routing (each service call resolves its own record
 * through AuthorizedQuery and answers 404 if it does not belong to this case's
 * tenant), the same shape CustomerContactController's own comment documents.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/cases/{caseId}")
public class MilestoneController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant, or write_scope refused this stage";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final MilestoneService milestones;
    private final RequirementService requirements;

    public MilestoneController(MilestoneService milestones, RequirementService requirements) {
        this.milestones = milestones;
        this.requirements = requirements;
    }

    /** Q15's manual override: reassign and/or reschedule. */
    @PutMapping("/milestones/{mid}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Saved -- a genuinely new owner also becomes an ASSIGNEE participant"),
            @ApiResponse(responseCode = "403", description = "write_scope refused this stage to a non-owner",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseMilestoneView update(@PathVariable UUID caseId, @PathVariable UUID mid,
                                @RequestBody UpdateMilestoneRequest request) {
        return milestones.update(mid, request);
    }

    /** The rework path -- Task 7 rejects backward branches, so this is an explicit action instead. */
    @PostMapping("/milestones/{mid}/reopen")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Reopened -- an ACTIVE milestone again, and its requirements OPEN"),
            @ApiResponse(responseCode = "400", description = "A blank reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void reopen(@PathVariable UUID caseId, @PathVariable UUID mid, @Valid @RequestBody ReasonRequest request) {
        milestones.reopen(mid, request.reason());
    }

    /** Requests Q5's forced completion; ApprovalController's force-requests endpoint decides it. */
    @PostMapping("/milestones/{mid}/force-complete")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A PENDING FORCE_COMPLETE approval, awaiting a decider"),
            @ApiResponse(responseCode = "400", description = "A blank reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ApprovalView requestForceComplete(@PathVariable UUID caseId, @PathVariable UUID mid,
                                             @Valid @RequestBody ReasonRequest request) {
        return milestones.requestForceComplete(mid, request.reason());
    }

    @PostMapping("/requirements/{rid}/satisfy")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Satisfied -- idempotent, a second call returns the current view"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The case is on hold",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseRequirementView satisfy(@PathVariable UUID caseId, @PathVariable UUID rid,
                                   @RequestBody SatisfyRequest request) {
        return requirements.satisfy(rid, request.ref(), request.refType());
    }

    @PostMapping("/requirements/{rid}/waive")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Waived -- idempotent, a second call returns the current view"),
            @ApiResponse(responseCode = "400", description = "A blank waiver reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The case is on hold",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseRequirementView waive(@PathVariable UUID caseId, @PathVariable UUID rid,
                                 @Valid @RequestBody ReasonRequest request) {
        return requirements.waive(rid, request.reason());
    }
}
