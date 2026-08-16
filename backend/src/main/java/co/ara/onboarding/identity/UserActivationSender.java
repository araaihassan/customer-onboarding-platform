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
}
