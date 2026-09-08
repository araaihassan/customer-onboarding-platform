package co.ara.onboarding.customer;

/**
 * A contact's corrected email collides with a DIFFERENT portal account's
 * address in the same tenant.
 *
 * {@code app_user} is unique on {@code (tenant_id, lower(email))} (V4)
 * TENANT-WIDE, while {@code customer_contact}'s own uniqueness (V14) is only
 * PER-CUSTOMER — so an address correction that passes
 * {@link CustomerContactService}'s own duplicate-contact check can still
 * collide with an unrelated customer's already-activated contact once
 * {@link LinkedPortalUserEmailSync} tries to sync it onto {@code app_user}.
 * This is that collision, surfaced as an actionable conflict rather than the
 * raw {@code DataIntegrityViolationException} a plain, unchecked
 * {@code save()} would produce at commit — the same failure shape
 * {@code CustomerContactService.save}'s own javadoc explains
 * {@code saveAndFlush} exists to prevent for the sibling constraint.
 *
 * Mapped to 409 by {@link CustomerExceptionHandler}, same as
 * {@link DuplicateContactEmailException}. The detail deliberately does not
 * echo the address back, for the same reason that exception's own comment
 * gives.
 */
public class PortalEmailConflictException extends RuntimeException {

    public PortalEmailConflictException() {
        super("This email address is already in use by another portal account in this tenant");
    }
}
