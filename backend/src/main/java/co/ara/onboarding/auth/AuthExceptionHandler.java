package co.ara.onboarding.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * auth's own exception-to-HTTP mapping.
 *
 * Lives here rather than in platform.ApiExceptionHandler for the same reason as
 * TenantExceptionHandler and AuthzExceptionHandler: auth depends on platform, so a
 * platform class naming InvalidTokenException would close a platform -> auth cycle
 * that ModuleBoundaryTest rejects.
 */
@RestControllerAdvice
public class AuthExceptionHandler {

    /**
     * A bare 400 with no reason. Unknown, expired, already used, revoked and
     * wrong-purpose all answer identically -- telling a caller their token "expired"
     * confirms it was once real, and telling them it is "already used" confirms
     * someone redeemed it.
     */
    @ExceptionHandler(InvalidTokenException.class)
    ProblemDetail invalidToken(InvalidTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid or expired token");
    }
}
