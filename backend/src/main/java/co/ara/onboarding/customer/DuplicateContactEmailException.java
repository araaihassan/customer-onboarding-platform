package co.ara.onboarding.customer;

/**
 * Two contacts on one customer cannot share an email address --
 * {@code UNIQUE (customer_id, email)} on customer_contact (V8).
 *
 * This exists because that collision is a foreseeable USER error rather than a
 * programming one: two people entering the same address, or an edit typed onto
 * an address a colleague already used. Left untranslated it surfaced as a bare
 * 500, which tells the caller that something broke rather than what to fix, and
 * a 500 for an input a user can correct is wrong on both sides of the wire.
 *
 * Mapped to 409 by CustomerExceptionHandler -- in this module, never in
 * platform, which must not name a domain type (ModuleBoundaryTest).
 *
 * The detail deliberately does NOT echo the address back. The caller supplied
 * it, so repeating it adds nothing, and a message assembled from tenant data is
 * the habit that eventually leaks some.
 */
public class DuplicateContactEmailException extends RuntimeException {

    public DuplicateContactEmailException(Throwable cause) {
        super("A contact with this email address already exists for this customer", cause);
    }
}
