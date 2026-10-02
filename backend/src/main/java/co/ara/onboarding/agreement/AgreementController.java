package co.ara.onboarding.agreement;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Every operator-side {@code agreement} endpoint of spec section 8. Thin: binds path, query
 * and body parameters and delegates to the already-gated services; no authorization or
 * lifecycle logic lives here, and no repository is reachable from this class.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class AgreementController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant, or write_scope refused this stage";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (identical response either way)";
    private static final String CONFLICT =
            "The agreement is not in a state that allows this, a rule such as the four-eyes review refused it, "
                    + "or its lockVersion is stale";
    private static final String BAD_REQUEST = "Validation failed";

    private final AgreementService agreements;
    private final AgreementReviewService review;
    private final AgreementSignatureService signatures;

    public AgreementController(AgreementService agreements, AgreementReviewService review,
                               AgreementSignatureService signatures) {
        this.agreements = agreements;
        this.review = review;
        this.signatures = signatures;
    }

    @GetMapping("/agreements")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Agreements visible to the caller, optionally by display status"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Page<AgreementView> list(@RequestParam(required = false) AgreementDisplayStatus status, Pageable pageable) {
        return agreements.list(status, pageable);
    }

    @GetMapping("/agreements/summary")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lifecycle counts, each respecting the caller's scope"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementSummaryView summary() {
        return agreements.summary();
    }

    @GetMapping("/cases/{caseId}/agreements")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A journey's agreements, live first"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public List<AgreementView> forCase(@PathVariable UUID caseId) {
        return agreements.forCase(caseId);
    }

    @GetMapping("/agreements/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The agreement with its versions, signatories and signatures"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView get(@PathVariable UUID id) {
        return agreements.get(id);
    }

    @PatchMapping("/agreements/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved agreement"),
            @ApiResponse(responseCode = "400", description = BAD_REQUEST,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView patch(@PathVariable UUID id, @Valid @RequestBody PatchAgreementRequest request) {
        return agreements.patch(id, request);
    }

    @PutMapping("/agreements/{id}/signatories")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The agreement with its replaced signatory list"),
            @ApiResponse(responseCode = "400", description = BAD_REQUEST,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView replaceSignatories(@PathVariable UUID id,
                                                  @Valid @RequestBody ReplaceSignatoriesRequest request) {
        return agreements.replaceSignatories(id, request);
    }

    @PostMapping(value = "/agreements/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The agreement with the new draft file version"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "The file exceeds app.storage.max-upload-bytes",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The sniffed content type is not accepted",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView uploadFile(@PathVariable UUID id, @RequestParam long lockVersion,
                                          @RequestPart("file") MultipartFile file) throws IOException {
        return agreements.uploadDraftFile(id, lockVersion, file.getInputStream(), file.getSize());
    }

    @PostMapping("/agreements/{id}/submit")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Submitted for review"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView submit(@PathVariable UUID id, @RequestBody LockVersionRequest request) {
        return agreements.submit(id, request.lockVersion());
    }

    @PostMapping("/agreements/{id}/versions/{versionNumber}/review")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The decision recorded"),
            @ApiResponse(responseCode = "400", description = BAD_REQUEST,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView review(@PathVariable UUID id, @PathVariable int versionNumber,
                                      @Valid @RequestBody ReviewAgreementRequest request) {
        return review.review(id, versionNumber, request);
    }

    @PostMapping("/agreements/{id}/send")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Sent for signature"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView send(@PathVariable UUID id, @RequestBody LockVersionRequest request) {
        return agreements.send(id, request.lockVersion());
    }

    /**
     * Multipart because the last signature of a file-backed agreement carries the countersigned
     * file: a JSON {@code signature} part, plus an optional {@code file} part.
     */
    @PostMapping(value = "/agreements/{id}/signatures", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The signature recorded"),
            @ApiResponse(responseCode = "400", description = BAD_REQUEST,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "The file exceeds app.storage.max-upload-bytes",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The sniffed content type is not accepted",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView recordSignature(@PathVariable UUID id,
                                               @Valid @RequestPart("signature") RecordSignatureRequest signature,
                                               @RequestPart(value = "file", required = false) MultipartFile file)
            throws IOException {
        if (file == null) {
            return signatures.record(id, signature, null, 0);
        }
        return signatures.record(id, signature, file.getInputStream(), file.getSize());
    }

    @PostMapping("/agreements/{id}/cancel")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled; a successor draft is created"),
            @ApiResponse(responseCode = "400", description = BAD_REQUEST,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = CONFLICT,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public AgreementDetailView cancel(@PathVariable UUID id, @Valid @RequestBody CancelAgreementRequest request) {
        return agreements.cancel(id, request);
    }
}
