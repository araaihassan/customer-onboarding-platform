package co.ara.onboarding.notification;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** notification's own exception mapping; platform may not name a domain type. */
@RestControllerAdvice
public class NotificationExceptionHandler {

    @ExceptionHandler(NotificationRuleException.class)
    ProblemDetail unprocessable(NotificationRuleException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
