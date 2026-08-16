package co.ara.onboarding.tenancy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Tenancy's own exception-to-HTTP mapping.
 *
 * This lives here rather than in {@code platform.ApiExceptionHandler} because
 * platform is the foundation every domain module builds on: tenancy already
 * depends on it (Tenant and TenantScopedEntity extend BaseEntity), so a
 * platform class naming a tenancy type closes a platform -> tenancy -> platform
 * cycle. ModuleBoundaryTest.noCyclesBetweenModules catches exactly that.
 *
 * The pattern generalizes: each module owns the mapping for the exceptions it
 * defines, and platform keeps only the framework-level ones it can express
 * without naming a domain.
 */
@RestControllerAdvice
public class TenantExceptionHandler {

    /**
     * An unresolvable tenant is indistinguishable from one the caller may not
     * see, so it surfaces as absent, never as forbidden (spec 6.8). The bare
     * "Not found" detail is deliberate -- naming the tenant or echoing the slug
     * would reintroduce the existence leak that returning 404 exists to
     * prevent.
     */
    @ExceptionHandler(UnknownTenantException.class)
    ProblemDetail unknownTenant(UnknownTenantException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }
}
