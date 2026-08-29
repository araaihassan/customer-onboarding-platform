package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.ContactInvitationSender;
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
 *
 * Implements customer.ContactInvitationSender so the customer API can offer "invite
 * this contact" without customer depending on auth — auth already depends on
 * customer, so the reverse edge would close a cycle. See that interface.
 */
@Service
public class InvitationService implements ContactInvitationSender {

    /**
     * Seven days: long enough to survive a holiday, short enough to expire.
     *
     * Public since Task R1 so provisioning issues the bootstrap administrator's
     * invitation on the same clock. One constant, so a change to the window cannot
     * apply to some activation invitations and not others.
     */
    public static final Duration ACTIVATION_TTL = Duration.ofDays(7);

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
    @Override
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

    /**
     * contactId is a foreign id CustomerContactService.update passes straight
     * through the ContactInvitationSender port, so — exactly like issue() above,
     * and like identity.UserActivationSender.revokePendingInvitations — it must be
     * re-resolved through AuthorizedQuery here rather than trusted just because
     * today's one caller already resolved the same contact under the same
     * permission a few lines earlier in its own method. ContactInvitationSender is
     * a public customer-module interface, injectable by anything; this keeps the
     * method's own safety self-contained rather than a property of its current
     * caller.
     *
     * Delegates the actual finder call to PendingInvitationRevoker, keyed on
     * customerContactId rather than userId — a contact invitation may predate any
     * linked app_user (InvitationService.issue itself never sets userId), so the
     * userId-keyed sweep Task 4 built cannot find it.
     */
    @Override
    @RequirePermission(PermissionKeys.CONTACT_MANAGE)
    @Transactional
    public void revokePendingInvitations(UUID contactId) {
        CustomerContact contact = authorizedQuery.getById(
                contacts, CustomerContact.class, PermissionKeys.CONTACT_MANAGE, contactId);
        new PendingInvitationRevoker(invitations).revokeForContact(contact.getId());
    }
}
