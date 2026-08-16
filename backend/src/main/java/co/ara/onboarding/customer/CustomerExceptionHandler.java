package co.ara.onboarding.customer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The customer module's own exception-to-HTTP mapping.
 *
 * Here rather than in {@code platform.ApiExceptionHandler} for the reason that
 * module's own comment gives: platform is the foundation every domain builds on,
 * and customer already depends on it, so a platform class naming
 * DuplicateContactEmailException would close a platform -> customer -> platform
 * cycle. ModuleBoundaryTest.noCyclesBetweenModules rejects exactly that.
 *
 * Extending IllegalStateException to borrow platform's 409 mapping would have
 * compiled and would have been wrong: that handler echoes the message verbatim,
 * which is safe only for the operator-facing conflicts it was written for. A
 * domain exception gets its own handler so the module stays in control of what
 * its detail says.
 */
@RestControllerAdvice
public class CustomerExceptionHandler {

    /**
     * 409, not 400 and not 500. The request is well formed and the caller is
     * authorized; it collides with a record that already exists, which is the
     * textbook conflict — and, unlike a 500, something the user can act on by
     * correcting the address.
     */
    @ExceptionHandler(DuplicateContactEmailException.class)
    ProblemDetail duplicateContactEmail(DuplicateContactEmailException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
