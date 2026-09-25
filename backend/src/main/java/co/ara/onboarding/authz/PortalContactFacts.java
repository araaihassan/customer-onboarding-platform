package co.ara.onboarding.authz;

import java.util.UUID;

/**
 * What {@link AuthorizationService} needs to know about a PORTAL actor's linked
 * {@code customer_contact}, to pick between {@link PortalPermissions#forContact()}
 * and {@link PortalPermissions#forSponsor()} -- and, since Task 13, what
 * {@code scoping.DocumentAudienceFilter}'s portal branch needs to resolve QA Q9's
 * three visibility tiers and label targeting without a second lookup of the same
 * row.
 *
 * Widened from {@code sponsor} alone (Task 3's original shape) to also carry
 * {@code id} (the contact's own row id -- CONTACT_ONLY ownership and the
 * document_share CONTACT-principal correlation both key off this, not the
 * app_user id), {@code customerId} (the "at my own customer" gate, denormalised
 * on {@code Document} for the same reason) and {@code label} (Q9's amendment,
 * nullable -- an unlabelled contact matches no label target). Still deliberately
 * narrow otherwise: no name, no email, nothing else customer_contact carries. A
 * wider fact shape would tempt a future caller to reach past
 * {@link PortalPermissions}/the audience filter for something this port was never
 * meant to answer.
 */
public record PortalContactFacts(UUID id, UUID customerId, String label, boolean sponsor) {

    /** Named to match the boolean-getter convention customer.CustomerContact already uses. */
    public boolean isSponsor() { return sponsor; }
}
