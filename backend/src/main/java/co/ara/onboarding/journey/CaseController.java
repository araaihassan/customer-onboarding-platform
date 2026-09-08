package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEventView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * Thin: binds path variables and delegates to {@link CaseService}. No authorization
 * logic here -- that is the gate's job, and ModuleBoundaryTest stops this class
 * reaching a repository.
 *
 * Two different path roots share this one class rather than splitting into a second
 * controller: "cases for a customer" and "a case by its own id" are both facets of
 * the same resource, and the plan names only CaseController/MilestoneController/
 * ApprovalController/MigrationController as Task 20's files.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class CaseController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant for this action";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final CaseService cases;
    private final TimelineService timeline;

    public CaseController(CaseService cases, TimelineService timeline) {
        this.cases = cases;
        this.timeline = timeline;
    }

    /** The switcher's payload: several cases for one customer, newest first (CaseService.listForCustomer). */
    @GetMapping("/customers/{customerId}/cases")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cases for the customer, newest first"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<CaseView> listForCustomer(@PathVariable UUID customerId) {
        return cases.listForCustomer(customerId);
    }

    @PostMapping("/cases")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Case opened, pinned to the template's current published version"),
            @ApiResponse(responseCode = "400", description = "A blank name failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The customer or template is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The template has no published version",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Every attribute problem found, so the dialog can list them all",
                    content = @Content(schema = @Schema(implementation = JourneyExceptionHandler.ProblemList.class)))
    })
    public CaseView create(@Valid @RequestBody CreateCaseRequest request) {
        return cases.create(request);
    }

    @GetMapping("/cases/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Header facets and customerId -- no customer name, no contacts (spec 3.2)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseView get(@PathVariable UUID id) {
        return cases.get(id);
    }

    @PutMapping("/cases/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved case"),
            @ApiResponse(responseCode = "400", description = "A blank name failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "Every attribute problem found, so the dialog can list them all",
                    content = @Content(schema = @Schema(implementation = JourneyExceptionHandler.ProblemList.class)))
    })
    public CaseView update(@PathVariable UUID id, @Valid @RequestBody UpdateCaseRequest request) {
        return cases.update(id, request);
    }

    /** Its own call so the header renders before the stage graph arrives. */
    @GetMapping("/cases/{id}/roadmap")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every stage, milestone and requirement, present from day one"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public RoadmapView roadmap(@PathVariable UUID id) {
        return cases.roadmap(id);
    }

    @PostMapping("/cases/{id}/advance")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Advanced -- a new current stage, or the case completed"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The current stage is not exitable yet",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseView advance(@PathVariable UUID id) {
        return cases.advance(id);
    }

    @PostMapping("/cases/{id}/hold")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Held, refusing further progress-changing writes"),
            @ApiResponse(responseCode = "400", description = "A blank reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseView hold(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.hold(id, request.reason());
    }

    @PostMapping("/cases/{id}/resume")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Resumed -- open due dates shifted by the elapsed business days"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The case is not on hold",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public CaseView resume(@PathVariable UUID id) {
        return cases.resume(id);
    }

    @GetMapping("/cases/{id}/participants")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every ACTIVE participant"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<ParticipantView> participants(@PathVariable UUID id) {
        return cases.participants(id);
    }

    @PostMapping("/cases/{id}/participants")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Added"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The case or the user is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void addParticipant(@PathVariable UUID id, @Valid @RequestBody AddParticipantRequest request) {
        cases.addParticipant(id, request.userId(), request.relationship());
    }

    /** POST .../remove, following customers/{id}/deactivate: DELETE is revoked on every journey table. */
    @PostMapping("/cases/{id}/participants/{userId}/remove")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed (status set to REMOVED, never deleted)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The case or the participant is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void removeParticipant(@PathVariable UUID id, @PathVariable UUID userId) {
        cases.removeParticipant(id, userId);
    }

    /**
     * Gated on case.view, not audit.view -- TimelineService's own javadoc explains
     * why AUDIT_VIEW's actor-scoped descriptor is the wrong axis for a case's
     * shared history.
     */
    @GetMapping("/cases/{id}/timeline")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every event recorded against the case, newest first"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Page<AuditEventView> timeline(@PathVariable UUID id, Pageable pageable) {
        return timeline.forCase(id, pageable);
    }
}
