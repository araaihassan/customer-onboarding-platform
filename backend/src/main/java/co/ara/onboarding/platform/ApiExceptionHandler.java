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
}
