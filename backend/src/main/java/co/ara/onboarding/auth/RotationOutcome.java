package co.ara.onboarding.auth;

import co.ara.onboarding.identity.AppUser;

/**
 * The result of attempting to rotate a refresh token, as data rather than as an
 * exception.
 *
 * This shape is load-bearing, not stylistic. Rejecting a replayed token also
 * revokes its whole family and writes a REFRESH_REUSE_DETECTED audit event, and
 * both of those are writes. Signalling the rejection by throwing would mark the
 * transaction rollback-only and discard them — the caller would receive its 401
 * while the stolen family stayed live and nothing recorded the attempt. Reuse
 * detection that does not actually revoke is worse than no reuse detection,
 * because it looks like it works.
 *
 * Same reasoning as LoginOutcome; see the note there about AuditRecorder's
 * MANDATORY propagation.
 */
public sealed interface RotationOutcome {

    record Rotated(AppUser user, String newRawToken) implements RotationOutcome {}

    /**
     * The reason is for logs, metrics and tests — never for the response body. The
     * client gets an identical 401 for every case, or the endpoint becomes an
     * oracle for which tokens exist and which merely expired.
     */
    record Rejected(Reason reason) implements RotationOutcome {}

    enum Reason {
        /** No such token in this tenant: never issued, or issued to another tenant. */
        UNKNOWN,
        /** Already rotated once. Treated as theft: the family is revoked. */
        REUSED,
        /** Past its expiry. */
        EXPIRED,
        /** The family was already revoked, by reuse detection or by logout. */
        REVOKED
    }
}
