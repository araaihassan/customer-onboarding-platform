package co.ara.onboarding.auth;

import co.ara.onboarding.platform.UserType;

import java.util.UUID;

/**
 * The result of an attempted login, as data rather than as an exception.
 *
 * This shape is deliberate. LoginService records a LOGIN_FAILED audit event before
 * rejecting, and AuditRecorder joins the caller's transaction
 * (@Transactional(MANDATORY)) — so throwing to signal failure would mark that
 * transaction rollback-only and discard the very audit row that records the
 * attempt. Returning an outcome lets the transaction commit, then leaves the
 * controller to choose a status code, which is its job anyway.
 *
 * Task 17's login throttling depends on failed attempts actually being durable.
 */
public sealed interface LoginOutcome {

    /**
     * refreshToken is the raw value, which exists only in this object and in the
     * Set-Cookie header the controller writes from it — the database holds a hash.
     */
    record Success(String accessToken, long expiresInSeconds, String refreshToken,
                   UUID userId, String fullName, UserType userType) implements LoginOutcome {}

    /**
     * One case for absent, inactive, and wrong-password alike. The caller must not
     * be able to tell them apart, or login becomes an account-existence oracle.
     */
    record InvalidCredentials() implements LoginOutcome {}

    /** Credentials were correct, but the account requires a second factor (spec 7.8). */
    record MfaRequired() implements LoginOutcome {}

    /**
     * Too many recent failures for this address (→ 429). Returned without even
     * looking at the password, and returned for unknown addresses too — if only real
     * accounts could lock out, the status itself would reveal which addresses exist.
     */
    record LockedOut() implements LoginOutcome {}
}
