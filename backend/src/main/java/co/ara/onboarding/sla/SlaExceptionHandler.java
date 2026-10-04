package co.ara.onboarding.sla;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** sla's own exception-to-HTTP mapping; platform may not name a domain type, so it lives here. */
@RestControllerAdvice
public class SlaExceptionHandler {

    @ExceptionHandler(UnprocessableException.class)
    ProblemDetail unprocessable(UnprocessableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
