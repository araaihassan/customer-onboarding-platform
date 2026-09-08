package co.ara.onboarding.identity;

import java.util.UUID;

/**
 * Issues an activation invitation for a newly created internal user.
 *
 * A port, for the same reason as customer.ContactInvitationSender: auth already
 * depends on identity, so identity calling auth directly would close
 * identity -> auth -> identity. auth implements this.
 *
 * The method name matches the implementation's exactly. A differently-named port
 * method delegating internally would be a self-invocation, bypassing the Spring
 * proxy and with it the @RequirePermission gate.
 */
public interface UserActivationSender {

    /** Returns the raw activation token, which is also emailed to the user. */
    String issueForUser(UUID userId);

    /**
     * Revokes every outstanding, unaccepted invitation for this user — activation
     * and password-reset alike, since both purposes share one table and this is
     * keyed only on userId. Called by UserAdminService.deactivate so a deactivated
     * account cannot be activated or have its password reset via a token issued
     * before the deactivation, or a fresh one issued after it.
     */
    void revokePendingInvitations(UUID userId);
}
