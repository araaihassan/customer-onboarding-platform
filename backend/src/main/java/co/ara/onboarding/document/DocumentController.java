package co.ara.onboarding.document;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.InputStreamResource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.springframework.http.HttpStatus.CREATED;

/**
 * Task 22: every {@code document} endpoint spec §8 lists EXCEPT the two
 * request/portal rows (Phase 5 -- Tasks 23/26/27) and the version-review row
 * (a later task's own controller, per the plan's own ruling). Thin,
 * following {@code programme.ProgrammeController}'s and {@code
 * task.TaskController}'s exact pattern: binds path/request parameters and
 * delegates straight to {@link DocumentService}/{@link
 * DocumentContentService}/{@link DocumentSharingService}, no authorization
 * logic here at all -- that is each service method's own
 * {@code @RequirePermission} gate's job, and {@code
 * ModuleBoundaryTest.controllersDoNotUseRepositoriesDirectly} stops this
 * class reaching a repository directly.
 *
 * <p>{@code POST .../documents} and {@code POST .../versions} are this
 * codebase's FIRST multipart endpoints (confirmed: no {@code MultipartFile}
 * usage anywhere in {@code backend/src/main} before this task) -- a
 * {@code file} part carries the bytes and, for the create path only, a
 * {@code metadata} part carries {@link CreateDocumentRequest} as a JSON body,
 * decoded through the identical {@code HttpMessageConverter} machinery
 * {@code @RequestBody} uses elsewhere; Spring's ordinary multipart/JSON-part
 * binding, not a bespoke mechanism.
 *
 * <p>{@code DELETE} is used for {@code .../shares/{shareId}} and
 * {@code .../links/{caseId}} because spec §8's own API table specifies it
 * that way for these two rows -- unlike {@code programme.ProgrammeController}'s
 * own POST-.../remove convention, this is not a mismatch: CLAUDE.md's
 * "DELETE is deny-by-default at the database layer" invariant is about SQL
 * {@code DELETE} statements, and neither {@link DocumentSharingService#revokeShare}
 * nor {@link DocumentSharingService#unlink} issues one -- both set a
 * {@code revoked_at} column, exactly like every other revocation in this
 * module. The HTTP verb and the SQL statement are independent choices.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}")
public class DocumentController {

    private static final String FORBIDDEN = "Caller holds no sufficient grant, or write_scope refused this stage";
    private static final String NOT_FOUND = "Absent, or out of the caller's scope (spec 6.8: identical response either way)";

    private final DocumentService documents;
    private final DocumentContentService content;
    private final DocumentSharingService sharing;

    public DocumentController(DocumentService documents, DocumentContentService content,
                              DocumentSharingService sharing) {
        this.documents = documents;
        this.content = content;
        this.sharing = sharing;
    }

    @GetMapping("/documents")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every document visible to the caller, scope + audience filtered"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Page<DocumentView> list(Pageable pageable) {
        return documents.list(pageable);
    }

    @GetMapping("/cases/{caseId}/documents")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Documents whose home is this case, or a live link into it"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public Page<DocumentView> forCase(@PathVariable UUID caseId, Pageable pageable) {
        return documents.forCase(caseId, pageable);
    }

    @PostMapping(value = "/cases/{caseId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Uploaded, pinned to version 1"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "The declared size exceeds app.storage.max-upload-bytes",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The sniffed content type is not accepted for the declared category",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentView upload(@PathVariable UUID caseId, @RequestPart("file") MultipartFile file,
                               @Valid @RequestPart("metadata") CreateDocumentRequest metadata) throws IOException {
        return documents.upload(caseId, metadata, file.getInputStream(), file.getSize(), file.getContentType());
    }

    @GetMapping("/documents/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The document's metadata"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentView get(@PathVariable UUID id) {
        return documents.get(id);
    }

    @PatchMapping("/documents/{id}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The saved document"),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentView patch(@PathVariable UUID id, @Valid @RequestBody PatchDocumentRequest request) {
        return documents.patch(id, request);
    }

    @PostMapping(value = "/documents/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "A new version appended, always starting PENDING"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "Two callers appended a version at the same moment; retry",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "413", description = "The declared size exceeds app.storage.max-upload-bytes",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "422", description = "The sniffed content type is not accepted for the document's category",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentVersionView addVersion(@PathVariable UUID id, @RequestPart("file") MultipartFile file)
            throws IOException {
        return documents.addVersion(id, file.getInputStream(), file.getSize(), file.getContentType());
    }

    /**
     * Streams the version's bytes with {@code Content-Disposition: attachment}
     * on EVERY response -- no branch that serves a document inline (Task 7's
     * ruling, spec §2.3/§7.6). Built through {@link ContentDisposition
     * #builder(String)}{@code .filename(name, StandardCharsets.UTF_8)}, never
     * by concatenating {@link BlobContent#filename()} into a raw header
     * value -- that field is free-form, user-supplied text with no length or
     * character restriction, so naive concatenation would be a
     * quote-breaking/CRLF header-injection vector (see that method's own
     * javadoc).
     *
     * <p>{@link ResponseEntity}{@code <InputStreamResource>} is the standard
     * Spring MVC idiom for streaming a response body without buffering it
     * into memory first -- {@code ResourceHttpMessageConverter} (which
     * handles any {@code Resource}, {@link InputStreamResource} included)
     * copies the stream directly to the servlet response's output stream via
     * {@code StreamUtils.copy}, and closes it in a {@code finally} block once
     * the copy completes or fails, so no explicit {@code close()} call is
     * needed here. No {@code Content-Length} header is set: {@link
     * BlobContent#sizeBytes()} is the caller's unverified DECLARED length
     * from upload time (see that record's own javadoc), never sniffed or
     * recomputed, and asserting it as a trusted transfer length here is
     * exactly the trust that javadoc warns against building -- the response
     * streams chunked instead, which needs no length claim to be correct.
     */
    @GetMapping("/documents/{id}/versions/{versionNo}/content")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The version's bytes, as an attachment"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The document, or that version number, is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id, @PathVariable int versionNo) {
        BlobContent blob = content.open(id, versionNo);
        ContentDisposition disposition = ContentDisposition.builder("attachment")
                .filename(blob.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(blob.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new InputStreamResource(blob.content()));
    }

    @PostMapping("/documents/{id}/retire")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Retired -- status set to RETIRED, shares and links revoked, never deleted"),
            @ApiResponse(responseCode = "400", description = "A blank reason failed validation",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = NOT_FOUND,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The case a satisfied requirement belongs to is currently ON_HOLD",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentView retire(@PathVariable UUID id, @Valid @RequestBody RetireDocumentRequest request) {
        return documents.retire(id, request.reason());
    }

    @PostMapping("/documents/{id}/shares")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Shared -- or the existing live share, returned idempotently"),
            @ApiResponse(responseCode = "400", description = "The principal belongs to a different customer than the document",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The document or the principal is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The document is retired, or two callers raced the same share",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentShareView share(@PathVariable UUID id, @Valid @RequestBody ShareDocumentRequest request) {
        return sharing.share(id, request.principalType(), request.principalId());
    }

    @DeleteMapping("/documents/{id}/shares/{shareId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Revoked (revoked_at set, never deleted) -- idempotent"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The share is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentShareView revokeShare(@PathVariable UUID id, @PathVariable UUID shareId) {
        return sharing.revokeShare(shareId);
    }

    @PostMapping("/documents/{id}/links")
    @ResponseStatus(CREATED)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Linked -- or the existing live link, returned idempotently"),
            @ApiResponse(responseCode = "400", description = "The target case belongs to a different customer than the document",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The document or the target case is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "409", description = "The document is retired, or two callers raced the same link",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentCaseLinkView link(@PathVariable UUID id, @Valid @RequestBody LinkDocumentRequest request) {
        return sharing.link(id, request.caseId());
    }

    @DeleteMapping("/documents/{id}/links/{caseId}")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Unlinked (revoked_at set, never deleted)"),
            @ApiResponse(responseCode = "403", description = FORBIDDEN,
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "404", description = "The document, the case, or a live link between them is absent or out of scope",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public DocumentCaseLinkView unlink(@PathVariable UUID id, @PathVariable UUID caseId) {
        return sharing.unlink(id, caseId);
    }
}
