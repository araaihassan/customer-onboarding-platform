package co.ara.onboarding.identity;

import java.util.UUID;

/**
 * Ends every live session a user holds.
 *
 * A port, for the same reason as {@link UserActivationSender}: refresh tokens
 * live in auth, auth already depends on identity, and identity calling auth
 * directly would close identity -> auth -> identity, which
 * ModuleBoundaryTest.noCyclesBetweenModules rejects. auth implements this.
 *
 * The method name matches the implementation's exactly. A differently-named port
 * method delegating internally would be a self-invocation, bypassing the Spring
 * proxy and with it the transaction the revocation must join.
 */
public interface UserSessionRevoker {

    /** Idempotent: revoking sessions for a user who holds none is not an error. */
    void revokeAllForUser(UUID userId);
}
