package co.ara.onboarding.provisioning;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * provisioning's own exception-to-HTTP mapping -- the first one this module has
 * needed.
 *
 * Here rather than in {@code platform.ApiExceptionHandler} for the reason that
 * module's own comment gives: platform is the foundation every domain builds on,
 * and provisioning already depends on it, so a platform class naming
 * DuplicateSlugException would close a platform -> provisioning -> platform
 * cycle. ModuleBoundaryTest.noCyclesBetweenModules rejects exactly that.
 */
@RestControllerAdvice
public class ProvisioningExceptionHandler {

    /**
     * 409, not 400 and not 500. The request is well formed -- the slug passed
     * bean validation -- and it collides with a tenant that already exists,
     * which is the textbook conflict, and something the caller can act on by
     * choosing a different slug.
     */
    @ExceptionHandler(DuplicateSlugException.class)
    ProblemDetail duplicateSlug(DuplicateSlugException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
