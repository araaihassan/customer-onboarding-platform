package co.ara.onboarding.document;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    @ExceptionHandler(UnacceptableContentTypeException.class)
    ProblemDetail onUnacceptableContentType(UnacceptableContentTypeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(UploadTooLargeException.class)
    ProblemDetail onUploadTooLarge(UploadTooLargeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage());
    }

    @ExceptionHandler({DocumentVersionConflictException.class, DuplicateDocumentShareException.class,
                       DuplicateDocumentCaseLinkException.class})
    ProblemDetail onConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
