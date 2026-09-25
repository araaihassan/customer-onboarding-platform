package co.ara.onboarding.document;

import co.ara.onboarding.platform.storage.StorageProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * document's own exception-to-HTTP mapping, following {@code
 * programme.ProgrammeExceptionHandler}'s and {@code task.TaskExceptionHandler}'s
 * exact pattern -- a package-private {@code @RestControllerAdvice} living in
 * the module that would otherwise define the domain exception, since {@code
 * platform.ApiExceptionHandler} must never name one (a domain type there
 * closes a {@code platform -> document -> platform} cycle,
 * {@code ModuleBoundaryTest.noCyclesBetweenModules}).
 *
 * {@code NoSuchElementException} (a foreign or out-of-scope id) and
 * {@code AccessDeniedException} are already mapped globally by {@code
 * platform.ApiExceptionHandler} to 404/403; so is {@code
 * IllegalStateException} (409) and {@code IllegalArgumentException} (400) --
 * which already covers every guard {@link DocumentSharingService} throws
 * directly (the retired-document refusals, the cross-customer
 * principal/case-mismatch checks) without this class naming either type.
 * {@code WriteScopeException}/{@code CaseOnHoldException} are likewise
 * already mapped globally, by {@code journey.JourneyExceptionHandler}.
 *
 * <p>What is left, and genuinely {@code document}'s own:
 * <ul>
 *   <li>{@link UnacceptableContentTypeException} -- 422, its own javadoc
 *       already states this status (Task 7's sniffed-content ruling).</li>
 *   <li>{@link UploadTooLargeException} -- 413, its own javadoc already
 *       states this status (Task 7's declared-size ceiling).</li>
 *   <li>{@link MaxUploadSizeExceededException} -- also 413 (Task 22 review
 *       Finding 1): the CONTAINER's own ceiling, {@code
 *       platform.storage.StorageConfig}'s {@code MultipartConfigElement} bean
 *       (bound to the same {@code app.storage.max-upload-bytes} value {@link
 *       UploadTooLargeException} is checked against), refusing a request
 *       before Spring MVC ever reaches a controller method -- so this handler,
 *       not {@link DocumentController}, is the only place that can map it.
 *       Left unmapped, this was a raw 500: a framework type with no handler
 *       anywhere, surfacing well before {@code DocumentService}'s own declared-
 *       size check could ever run for a large enough upload. Mapped here, not
 *       in {@code platform.ApiExceptionHandler}, purely because it is only
 *       ever thrown by this module's multipart endpoints today -- naming it in
 *       {@code platform} would be premature generalisation, not a module-cycle
 *       problem (it is a {@code org.springframework.web.multipart} type, not a
 *       domain one).</li>
 *   <li>{@link DocumentVersionConflictException}, {@link
 *       DuplicateDocumentShareException} and {@link
 *       DuplicateDocumentCaseLinkException} -- one combined 409 handler,
 *       the identical {@code ProgrammeExceptionHandler.onConflict} shape:
 *       the caller CAN see and target this record, the request was
 *       understood, and the refusal is retryable once the race resolves or
 *       the state changes. The latter two exceptions carried a bare
 *       {@code @ResponseStatus(HttpStatus.CONFLICT)} directly on their class
 *       (Tasks 19/20, before this handler existed) -- removed now that this
 *       handler takes over, because a bare {@code @ResponseStatus} produces
 *       Spring's default (bare, non-{@link ProblemDetail}) error body, which
 *       would have made these two exceptions' response shape inconsistent
 *       with every other conflict in this module and the wider API (visible
 *       in the OpenAPI document and {@code generated.ts}'s own error-shape
 *       typing) purely because of when in the plan they were introduced.
 *       {@code ExceptionHandlerExceptionResolver} (which resolves
 *       {@code @ExceptionHandler} methods) runs before {@code
 *       ResponseStatusExceptionResolver} in Spring's resolver chain, so this
 *       handler would have silently overridden the bare annotation's status
 *       code translation anyway even without removing it -- removing it
 *       simply makes that fact visible in the code instead of leaving a
 *       vestigial annotation nothing reaches.</li>
 * </ul>
 */
@RestControllerAdvice
class DocumentExceptionHandler {

    private final StorageProperties storage;

    DocumentExceptionHandler(StorageProperties storage) {
        this.storage = storage;
    }

    @ExceptionHandler(UnacceptableContentTypeException.class)
    ProblemDetail onUnacceptableContentType(UnacceptableContentTypeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ProblemDetail onUploadTooLarge(UploadTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    /**
     * Same status, same {@link ProblemDetail} shape as {@link
     * #onUploadTooLarge} -- a caller should not be able to tell, from the
     * response alone, whether the container's own multipart ceiling or the
     * application's declared-size check is what refused the upload.
     *
     * <p>Deliberately does NOT echo {@link MaxUploadSizeExceededException#getMaxUploadSize()}:
     * verified against {@code StandardMultipartHttpServletRequest.handleParseFailure}'s
     * own source that the {@code StandardServletMultipartResolver} path this
     * application uses always constructs this exception as {@code new
     * MaxUploadSizeExceededException(-1, ex)} -- the real ceiling is never
     * threaded through from the servlet container's own parse failure, so
     * {@code getMaxUploadSize()} is reliably {@code -1} here, not the
     * configured value. {@link StorageProperties#getMaxUploadBytes()} is the
     * one place that value is actually known.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ProblemDetail onMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        long max = e.getMaxUploadSize() >= 0 ? e.getMaxUploadSize() : storage.getMaxUploadBytes();
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE,
                "Upload exceeds the " + max + " byte multipart ceiling (app.storage.max-upload-bytes)");
    }

    @ExceptionHandler({DocumentVersionConflictException.class, DuplicateDocumentShareException.class,
                       DuplicateDocumentCaseLinkException.class})
    ProblemDetail onConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
