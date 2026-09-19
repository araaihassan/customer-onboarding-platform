package co.ara.onboarding.document;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Task 23: the two {@code document.request} rows spec Section 8 lists (
 * {@code POST /cases/{caseId}/document-requests} and {@code POST
 * /document-requests/{id}/withdraw}), following {@link DocumentController}'s
 * exact thin-controller pattern -- binds path/request parameters and
 * delegates straight to {@link DocumentRequestService}, no authorization
 * logic here at all.
 *
 * <p><b>This class is not in the plan's own Task 23 "Files" line</b>, but the
 * plan's own File Structure overview names {@code DocumentRequestController.java}
 * as an expected file, and no task in the 37-task plan (this one included, as
 * originally scoped) ever created it -- a real, pre-existing gap that would
 * have left every {@code document-requests} HTTP endpoint permanently
 * unreachable had it gone unnoticed. Closed here, alongside the service layer
 * this task already builds, rather than left for a task that would never come
 * looking for it.
 *
 * <p><b>Correction (Task 25):</b> this javadoc previously claimed "neither
 * Task 25's {@code fulfil} nor Task 27's {@code review} touch this
 * controller." That was wrong for {@code fulfil} -- neither design spec
 * Section 8's own endpoint table nor Task 25's own brief names an HTTP
 * surface for it, but Task 30's frontend hook list names {@code
 * useFulfilRequest} as its own distinct hook and Task 36's e2e spec drives
 * "request -&gt; fulfil -&gt; review -&gt; satisfy" as a real step in a live
 * browser flow -- neither is possible without one. {@code fulfil} now has a
 * real endpoint here (below), matching {@code withdraw}'s own sibling path
 * shape exactly. {@code review} is unaffected by this correction and still
 * lands on the EXISTING {@link DocumentController} instead, per the plan's
 * own ruling.
 *
 * <p>This class deliberately needs no new exception mapping of its own: every
 * exception {@link DocumentRequestService} throws directly ({@link
 * IllegalArgumentException}, {@link IllegalStateException}, {@link
 * java.util.NoSuchElementException}) is already mapped globally by {@code
 * platform.ApiExceptionHandler} to 400/409/404 respectively, and {@code
 * WriteScopeException}/{@code CaseOnHoldException} are already mapped
 * globally by {@code journey.JourneyExceptionHandler} -- the identical
 * "nothing new to register" state {@link DocumentSharingService}'s own
 * exceptions were in before {@code DocumentExceptionHandler} existed for its
 * two genuinely new conflict types, neither of which this class has.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class DocumentRequestController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant, or write_scope refused this stage";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final DocumentRequestService requests;

    public DocumentRequestController(DocumentRequestService requests) {
        this.requests = requests;
    }

    @PostMapping("/cases/{caseId}/document-requests")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Requested -- an ad-hoc request, requirementId always null"),
            @ApiResponse(responseCode = "400", description = "The contact belongs to a different customer than the case",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentRequestView create(@PathVariable UUID caseId,
                                      @Valid @RequestBody CreateDocumentRequestRequest request) {
        return requests.create(caseId, request);
    }

    @PostMapping("/document-requests/{id}/withdraw")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Withdrawn -- status set to WITHDRAWN, idempotent, never satisfies its requirement"),
            @ApiResponse(responseCode = "400", description = "A blank reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The request is already fulfilled",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentRequestView withdraw(@PathVariable UUID id,
                                        @Valid @RequestBody WithdrawDocumentRequestRequest request) {
        return requests.withdraw(id, request.reason());
    }

    @PostMapping("/document-requests/{id}/fulfil")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Fulfilled -- status set to FULFILLED and "
                    + "fulfilledDocumentId set; a linked requirement is satisfied immediately only when "
                    + "requiresReview is false"),
            @ApiResponse(responseCode = "400", description = "The document belongs to a different case than the request",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The request is not open (already fulfilled or withdrawn)",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentRequestView fulfil(@PathVariable UUID id,
                                      @Valid @RequestBody FulfilDocumentRequestRequest request) {
        return requests.fulfil(id, request.documentId());
    }
}
