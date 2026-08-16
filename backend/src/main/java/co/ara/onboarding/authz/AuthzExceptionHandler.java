package co.ara.onboarding.authz;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Authorization module's own exception-to-HTTP mapping.
 *
 * Lives here, not in platform.ApiExceptionHandler, for the same reason
 * TenantExceptionHandler does: authz depends on platform (Role extends
 * TenantScopedEntity extends BaseEntity), so a platform class naming
 * InvalidGrantException would close a platform -> authz -> tenancy -> platform
 * cycle. ModuleBoundaryTest.noCyclesBetweenModules rejects it.
 */
@RestControllerAdvice
public class AuthzExceptionHandler {

    /**
     * 400, not 403. A grant naming an unknown permission or a disallowed scope is
     * a malformed request; the caller may be perfectly authorized to manage roles.
     * Conflating the two would tell a legitimate admin they lack permission.
     */
    @ExceptionHandler(InvalidGrantException.class)
    ProblemDetail invalidGrant(InvalidGrantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
