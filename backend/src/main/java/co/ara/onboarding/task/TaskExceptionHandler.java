package co.ara.onboarding.task;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * task's own exception-to-HTTP mapping. NoSuchElementException and
 * AccessDeniedException are already handled globally by platform.ApiExceptionHandler
 * (404 / 403), and so is IllegalArgumentException (400) -- see that class's own
 * doc comment for why a plain java.lang type belongs there rather than here, next
 * to IllegalStateException's existing precedent. IllegalTaskTransitionException
 * is the one type this module defines that platform cannot name without closing
 * a platform -> task -> platform cycle (ModuleBoundaryTest), so it gets its own
 * handler here instead, following journey.JourneyExceptionHandler's exact
 * pattern (a package-private @RestControllerAdvice in the owning module).
 */
@RestControllerAdvice
class TaskExceptionHandler {

    /**
     * 409, matching journey.JourneyExceptionHandler's own conflict reasoning:
     * the caller asked for a transition TaskService.TRANSITIONS draws no edge
     * for -- the current status simply does not allow it -- and it is
     * retryable once the task's own status actually changes, which is what
     * separates this from a 422.
     */
    @ExceptionHandler(IllegalTaskTransitionException.class)
    ProblemDetail onIllegalTransition(IllegalTaskTransitionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
