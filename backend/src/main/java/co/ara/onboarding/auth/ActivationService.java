package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * ACCEPTING an invitation — performed by an unauthenticated person holding a token.
 *
 * NOT permission-gated, and it cannot be: the caller has no session and no roles;
 * the token is the entire credential. AuthorizationCoverageTest excludes this class
 * in the pre-authentication category. Issuing, which IS gated, is in
 * InvitationService.
 */
@Service
public class ActivationService {

    private final InvitationRepository invitations;
    private final CustomerContactRepository contacts;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final AuditRecorder audit;

    public ActivationService(InvitationRepository invitations, CustomerContactRepository contacts,
                             AppUserRepository users, PasswordEncoder passwords, AuditRecorder audit) {
        this.invitations = invitations;
        this.contacts = contacts;
        this.users = users;
        this.passwords = passwords;
        this.audit = audit;
    }

    /**
     * Creates the PORTAL user and links it to the contact.
     *
     * Throwing rather than returning an outcome is correct here, unlike login and
     * refresh: nothing is written before a rejection, so there is no durable state
     * for the rollback to discard.
     */
    @Transactional
    public AppUser accept(String rawToken, String rawPassword) {
        Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken))
                .filter(i -> i.isRedeemable(InvitationPurpose.ACTIVATION, Instant.now()))
                .orElseThrow(() -> new InvalidTokenException("Invitation is not redeemable"));

        CustomerContact contact = contacts.findById(invitation.getCustomerContactId())
                .orElseThrow(() -> new InvalidTokenException("Invitation has no contact"));

        // app_user is unique on (tenant, lower(email)), so an address that already has
        // an account would fail the constraint and surface as a 500. Rejected as an
        // invalid token instead, which is also the honest answer: this invitation
        // cannot create the account it promises.
        if (users.findByTenantIdAndEmailIgnoreCase(contact.getTenantId(), contact.getEmail()).isPresent()) {
            throw new InvalidTokenException("An account already exists for this address");
        }

        AppUser user = new AppUser();
        user.setId(Uuid7.generate());
        user.setTenantId(contact.getTenantId());
        user.setEmail(contact.getEmail());
        user.setFullName(contact.getFullName());
        user.setPasswordHash(passwords.encode(rawPassword));
        // PORTAL, never INTERNAL: this account belongs to a customer contact, and the
        // type is what keeps it out of internal role assignment and the staff app.
        user.setUserType(UserType.PORTAL);
        user.setStatus(UserStatus.ACTIVE);
        users.saveAndFlush(user);

        contact.setUserId(user.getId());
        contacts.save(contact);

        invitation.setAcceptedAt(Instant.now());
        invitations.save(invitation);

        audit.record(AuditActions.INVITATION_ACCEPTED, "app_user", user.getId(),
                "Portal invitation accepted by " + contact.getEmail(), Map.of());

        return user;
    }
}
