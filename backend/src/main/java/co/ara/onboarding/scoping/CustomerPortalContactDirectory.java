package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.PortalContactDirectory;
import co.ara.onboarding.authz.PortalContactFacts;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * scoping's side of the authz PortalContactDirectory port. The dependency runs
 * scoping -> authz and scoping -> customer/identity, never the reverse -- the
 * same shape CustomerContactDescriptor and AppUserDescriptor already establish
 * in this package, and the reason this adapter lives here rather than in
 * customer itself: resolving it needs identity's app_user.status too.
 *
 * Both status checks are independent and both must pass: an app_user that is
 * no longer ACTIVE resolves nothing even if the linked contact is still
 * ACTIVE, and vice versa. That is what makes retirement -- of either the
 * portal login or the contact record -- take effect on the very next request.
 */
@Component
public class CustomerPortalContactDirectory implements PortalContactDirectory {

    private final CustomerContactRepository contacts;
    private final AppUserRepository users;

    public CustomerPortalContactDirectory(CustomerContactRepository contacts, AppUserRepository users) {
        this.contacts = contacts;
        this.users = users;
    }

    /**
     * "Sponsor" is read off the customer's primary contact today -- the one
     * signal customer_contact already carries that distinguishes a customer's
     * main point of contact from any other. sub-project 4's own Task 8 adds a
     * customer_contact.label column, but that is free-text Q9 audience
     * targeting ("Finance", "Legal", "IT"), a different axis entirely, not a
     * sponsor flag -- see docs/superpowers/specs/2026-09-12-documents-design.md
     * §4.6. If a later sub-project gives "sponsor" its own dedicated
     * representation, this is the one place that needs to change.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<PortalContactFacts> findActiveContactForUser(UUID userId) {
        return users.findById(userId)
                .filter(u -> u.getStatus() == UserStatus.ACTIVE)
                .flatMap(u -> contacts.findByUserId(userId))
                .filter(c -> c.getStatus() == ContactStatus.ACTIVE)
                .map(c -> new PortalContactFacts(c.isPrimaryContact()));
    }
}
