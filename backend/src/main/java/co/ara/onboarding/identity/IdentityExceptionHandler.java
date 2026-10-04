package co.ara.onboarding.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * identity's own exception-to-HTTP mapping, in the same shape as {@code
 * document.DocumentExceptionHandler}: {@code platform} must never name a domain type.
 */
@RestControllerAdvice
class IdentityExceptionHandler {

    @ExceptionHandler(ReportingLineException.class)
    ProblemDetail onReportingLine(ReportingLineException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
