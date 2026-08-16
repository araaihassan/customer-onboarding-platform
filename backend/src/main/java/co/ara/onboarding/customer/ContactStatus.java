package co.ara.onboarding.customer;

/**
 * Whether the contact is still a live contact for the customer.
 *
 * Deliberately only two values, and deliberately not a portal-access state: a
 * contact's ability to log in is read from the linked AppUser.status. Duplicating
 * it here would create two sources of truth that could disagree, and the one
 * consulted at login would win silently.
 */
public enum ContactStatus { ACTIVE, INACTIVE }
