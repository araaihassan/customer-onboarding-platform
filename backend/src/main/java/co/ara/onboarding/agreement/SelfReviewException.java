package co.ara.onboarding.agreement;

import java.util.UUID;

/**
 * Spec 5.3 / invariant 7: the four-eyes rule. Whoever submitted this version, or last
 * edited it before submission, cannot also be the one who reviews it -- checked in
 * {@link AgreementReviewService} itself, never only in the UI. 409 through the global
 * {@code IllegalStateException} mapping ({@code platform.ApiExceptionHandler}); Task 21
 * gives it a clearer {@code ProblemDetail} of its own.
 */
public class SelfReviewException extends IllegalStateException {

    public SelfReviewException(UUID agreementId, int versionNumber) {
        super("Agreement " + agreementId + " v" + versionNumber
                + " cannot be reviewed by its own submitter or last editor");
    }
}
