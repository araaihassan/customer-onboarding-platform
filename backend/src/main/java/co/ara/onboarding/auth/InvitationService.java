package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * ISSUING invitations — an authenticated, permission-gated staff action.
 *
 * Accepting one is a different operation performed by a different actor and lives
 * in ActivationService. That split is not cosmetic: invitation.send is a real
 * catalogued permission so issuing must be gated, while accepting is performed by
 * someone holding only a token and cannot be. A single service carrying both could
 * not satisfy AuthorizationCoverageTest without exempting the gated half as well.
 */
@Service
public class InvitationService {

    /** Seven days: long enough to survive a holiday, short enough to expire. */
    static final Duration ACTIVATION_TTL = Duration.ofDays(7);

    private final InvitationRepository invitations;
    private final CustomerContactRepository contacts;
    private final AuthorizedQuery authorizedQuery;
    private final EmailSender email;
    private final AuditRecorder audit;

    public InvitationService(InvitationRepository invitations, CustomerContactRepository contacts,
                             AuthorizedQuery authorizedQuery, EmailSender email, AuditRecorder audit) {
        this.invitations = invitations;
        this.contacts = contacts;
        this.authorizedQuery = authorizedQuery;
        this.email = email;
        this.audit = audit;
    }

    /**
     * Returns the raw token, which is also emailed. It exists in memory and in that
     * email only — the row holds a hash.
     *
     * The contact is read through AuthorizedQuery rather than the repository, so
     * invitation.send is applied at record level too: a contact outside the caller's
     * scope is not found, and answers 404 rather than being invitable.
     */
    @RequirePermission(PermissionKeys.INVITATION_SEND)
    @Transactional
    public String issue(UUID contactId) {
        CustomerContact contact = authorizedQuery.getById(
                contacts, CustomerContact.class, PermissionKeys.INVITATION_SEND, contactId);

        String raw = SecureTokens.generate();
        Instant now = Instant.now();

        Invitation invitation = new Invitation();
        invitation.setId(Uuid7.generate());
        invitation.setTenantId(TenantContext.getRequired());
        invitation.setPurpose(InvitationPurpose.ACTIVATION);
        invitation.setCustomerContactId(contact.getId());
        invitation.setTokenHash(SecureTokens.hash(raw));
        invitation.setExpiresAt(now.plus(ACTIVATION_TTL));
        invitations.save(invitation);

        email.send(new EmailMessage(contact.getEmail(), "Activate your portal account",
                "Use this token to activate your account: " + raw));

        audit.record(AuditActions.INVITATION_SENT, "customer_contact", contact.getId(),
                "Portal invitation sent to " + contact.getEmail(), Map.of());

        return raw;
    }
}
