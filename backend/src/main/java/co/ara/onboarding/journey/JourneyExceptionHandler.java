package co.ara.onboarding.journey;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * journey's own exception-to-HTTP mapping. NoSuchElementException and
 * AccessDeniedException are already handled globally by platform.ApiExceptionHandler
 * (404 / 403); everything here is a journey-specific type that module cannot name
 * without closing a platform -> journey -> platform cycle (ModuleBoundaryTest).
 */
@RestControllerAdvice
class JourneyExceptionHandler {

    /**
     * 422, not 400: the request was understood and semantically rejected, and the
     * create-case dialog renders each problem against its own field.
     */
    @ExceptionHandler(AttributeValidationException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    ProblemList onAttributes(AttributeValidationException e) {
        return new ProblemList(e.problems());
    }

    /**
     * 409: the caller asked for something the case's current state does not
     * allow. All of these are retryable once the state changes, which is what
     * separates them from a 422.
     */
    @ExceptionHandler({StageNotExitableException.class, CaseOnHoldException.class,
                       CaseNotOnHoldException.class, ApprovalAlreadyDecidedException.class,
                       ApprovalKindMismatchException.class, CaseNotMigratableException.class,
                       TemplateNotPublishedException.class})
    ProblemDetail onConflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    /**
     * 403, deliberately not 404. The caller can see the record -- it is on their
     * screen -- so a 404 would be a lie the UI cannot explain. Safe precisely
     * because write_scope only ever subtracts from access the actor demonstrably
     * already has (spec 6.8's out-of-scope-is-404 rule is about hiding a record's
     * existence, not about this).
     */
    @ExceptionHandler({WriteScopeException.class, SelfApprovalException.class})
    ProblemDetail onRefused(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    public record ProblemList(List<String> problems) {}
}
