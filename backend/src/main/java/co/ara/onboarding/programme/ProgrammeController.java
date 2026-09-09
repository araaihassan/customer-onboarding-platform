package co.ara.onboarding.programme;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;
import static org.springframework.http.HttpStatus.NO_CONTENT;

/**
 * The {@code programme} module's first controller (Task 14). Thin, following
 * {@code journey.CaseController}'s own pattern exactly: binds path variables
 * and delegates straight to {@link ProgrammeService}/{@link
 * ProgrammeMembershipService}, no authorization logic here -- that is each
 * service method's own {@code @RequirePermission} gate's job, and
 * {@code ModuleBoundaryTest.controllersDoNotUseRepositoriesDirectly} stops
 * this class reaching a repository.
 *
 * Journey/participant membership follow {@code CaseController}'s own
 * add/remove shape: a plain {@code POST .../journeys} to link, and
 * {@code POST .../journeys/{caseId}/remove} to unlink -- DELETE is revoked at
 * the database for every business table in this codebase, so removal is
 * always a POST setting a status column, never an HTTP DELETE.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class ProgrammeController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant for this action";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final ProgrammeService programmes;
    private final ProgrammeMembershipService membership;

    public ProgrammeController(ProgrammeService programmes, ProgrammeMembershipService membership) {
        this.programmes = programmes;
        this.membership = membership;
    }

    @PostMapping("/programmes")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Programme created"),
            @ApiResponse(responseCode = "400", description = "A blank name failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The customer is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ProgrammeView create(@Valid @RequestBody CreateProgrammeRequest request) {
        return programmes.create(request);
    }

    @GetMapping("/programmes/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The programme, its visible journeys, and the "
                    + "duration-weighted rollup over them"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ProgrammeDetailView get(@PathVariable UUID id) {
        return programmes.get(id);
    }

    @PutMapping("/programmes/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved programme"),
            @ApiResponse(responseCode = "400", description = "A blank name failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The programme is deactivated",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ProgrammeView update(@PathVariable UUID id, @Valid @RequestBody UpdateProgrammeRequest request) {
        return programmes.update(id, request);
    }

    @PostMapping("/programmes/{id}/deactivate")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Deactivated (status set to INACTIVE, never deleted)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void deactivate(@PathVariable UUID id) {
        programmes.deactivate(id);
    }

    @PostMapping("/programmes/{id}/journeys")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Linked"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The programme or the case is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The programme is deactivated, or the case is "
                    + "already actively linked to a programme",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void addJourney(@PathVariable UUID id, @Valid @RequestBody AddJourneyRequest request) {
        membership.addJourney(id, request);
    }

    /** POST .../remove, following CaseController's own participant-removal shape. */
    @PostMapping("/programmes/{id}/journeys/{caseId}/remove")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed (removed_at set, never deleted)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The programme or the link is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The programme is deactivated",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void removeJourney(@PathVariable UUID id, @PathVariable UUID caseId) {
        membership.removeJourney(id, caseId);
    }

    @PostMapping("/programmes/{id}/participants")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Added -- read of the programme container only "
                    + "unless alsoGrantJourneyAccess is set (design spec §6.3)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The programme or the user is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The programme is deactivated, or the user is "
                    + "already an active participant",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void addParticipant(@PathVariable UUID id, @Valid @RequestBody AddProgrammeParticipantRequest request) {
        membership.addParticipant(id, request);
    }

    /** POST .../remove, following CaseController's own participant-removal shape. */
    @PostMapping("/programmes/{id}/participants/{userId}/remove")
    @ResponseStatus(NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Removed (status set to REMOVED, never deleted)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The programme or the participant is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The programme is deactivated",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public void removeParticipant(@PathVariable UUID id, @PathVariable UUID userId) {
        membership.removeParticipant(id, userId);
    }
}
