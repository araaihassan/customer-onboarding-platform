package co.ara.onboarding.authz;

/**
 * What {@link AuthorizationService} needs to know about a PORTAL actor's linked
 * {@code customer_contact}, to pick between {@link PortalPermissions#forContact()}
 * and {@link PortalPermissions#forSponsor()}.
 *
 * Deliberately narrow: no name, no email, nothing else customer_contact carries.
 * A wider fact shape would tempt a future caller to reach past
 * {@link PortalPermissions} for something this port was never meant to answer.
 */
public record PortalContactFacts(boolean sponsor) {

    /** Named to match the boolean-getter convention customer.CustomerContact already uses. */
    public boolean isSponsor() { return sponsor; }
}
