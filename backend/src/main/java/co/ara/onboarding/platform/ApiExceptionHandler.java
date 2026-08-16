package co.ara.onboarding.platform;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.NoSuchElementException;

/**
 * Framework-level exception mapping only.
 *
 * Handlers for exceptions a domain module defines belong in that module — see
 * {@code tenancy.TenantExceptionHandler}. platform is what every module depends
 * on, so naming a domain type here closes a dependency cycle that
 * ModuleBoundaryTest.noCyclesBetweenModules rejects.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Out-of-scope records surface as absent, never as forbidden (spec 6.8). */
    @ExceptionHandler(NoSuchElementException.class)
    ProblemDetail notFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail denied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Forbidden");
    }

    /**
     * 409 for a state conflict — a role that still has users assigned, for
     * instance. IllegalStateException is a java.lang type, so mapping it here
     * names no domain module and introduces no dependency.
     *
     * The message is echoed deliberately: these are operator-facing conflicts
     * ("disable it instead") rather than anything derived from tenant data. Any
     * domain exception whose message could leak record existence must get its own
     * handler in its own module instead, returning a bare detail.
     */
    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
