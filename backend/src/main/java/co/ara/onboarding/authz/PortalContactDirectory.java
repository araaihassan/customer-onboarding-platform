package co.ara.onboarding.authz;

import java.util.Optional;
import java.util.UUID;

/**
 * The facts {@link AuthorizationService} needs about a PORTAL actor's linked
 * customer contact, to resolve {@link PortalPermissions} without authz depending
 * on customer.
 *
 * A port, declared by the consumer and implemented by the provider -- the same
 * inversion {@link ActorDirectory} and {@code journey.CustomerDirectory} already
 * establish. customer_contact is customer's table, and customer already depends
 * on authz (its services are permission-gated), so an authz -> customer import
 * would close a cycle ModuleBoundaryTest rejects -- the identical reasoning
 * ActorDirectory's own javadoc gives for identity.
 *
 * co.ara.onboarding.scoping implements this, not customer itself: resolving it
 * also needs identity's app_user.status, and scoping is the module that already
 * crosses both boundaries for its descriptors (see CustomerContactDescriptor,
 * AppUserDescriptor).
 */
public interface PortalContactDirectory {

    /**
     * Empty when the user has no ACTIVE customer_contact linked, or is not
     * itself an ACTIVE app_user -- either collapses to "no authority", mirroring
     * AuthorizationService's own app_user.status = 'ACTIVE' join for internal
     * actors. Checking both here, rather than trusting the caller already
     * resolved an ACTIVE actor, is what makes a retired contact resolve nothing
     * on the very next request rather than when a token expires.
     */
    Optional<PortalContactFacts> findActiveContactForUser(UUID userId);
}
