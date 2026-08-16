package co.ara.onboarding.customer;

import java.util.UUID;

/**
 * Issues a portal activation invitation for a contact.
 *
 * A port, and it exists to keep the dependency pointing one way. The customer API
 * needs to offer "invite this contact", but the implementation lives in auth — and
 * auth already depends on customer, because InvitationService reads
 * CustomerContactRepository. A direct call from customer to auth would close
 * customer → auth → customer, which ModuleBoundaryTest rejects.
 *
 * So customer declares what it needs and auth provides it: InvitationService is the
 * implementation. Same shape as authz's ActorDirectory.
 */
public interface ContactInvitationSender {

    /**
     * Returns the raw activation token, which is also emailed to the contact.
     *
     * Named to match the implementing method exactly, on purpose. A differently-named
     * port method delegating to the real one would be a self-invocation inside the
     * bean, which bypasses the Spring proxy — and with it the @RequirePermission gate
     * on the implementation. One name, one gated method, no way around it.
     */
    String issue(UUID contactId);
}
