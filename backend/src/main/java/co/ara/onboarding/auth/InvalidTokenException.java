package co.ara.onboarding.auth;

/**
 * An activation or reset token is unusable: unknown, already redeemed, revoked,
 * expired, or presented for the wrong purpose.
 *
 * One exception for all of those on purpose. Mapped to 400 by AuthExceptionHandler
 * with no detail, so a caller holding a token cannot learn which of those it is —
 * "expired" would confirm it was once real.
 */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) { super(message); }
}
