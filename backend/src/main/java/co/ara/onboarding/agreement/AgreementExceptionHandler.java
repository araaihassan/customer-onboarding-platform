package co.ara.onboarding.agreement;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * agreement's own exception-to-HTTP mapping. Lives here, never in {@code platform}, which
 * must not name a domain type. Scoped to the two agreement controllers and ordered first so
 * these two mappings win over the global handlers ({@code platform.ApiExceptionHandler}'s
 * {@code IllegalStateException} 409 and {@code workflow.WorkflowExceptionHandler}'s
 * {@code OptimisticLockingFailureException} 409 -- both already map the status; these only
 * give the user a message they can act on, without leaking Hibernate's own wording).
 */
@RestControllerAdvice(assignableTypes = {AgreementController.class, PortalAgreementController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
class AgreementExceptionHandler {

    @ExceptionHandler(SelfReviewException.class)
    ProblemDetail onSelfReview(SelfReviewException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "You submitted or last edited this version, so someone else must review it.");
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail onStale(ObjectOptimisticLockingFailureException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "This agreement changed since you loaded it — reload and try again.");
    }
}
