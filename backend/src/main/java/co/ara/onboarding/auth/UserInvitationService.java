package co.ara.onboarding.auth;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserActivationSender;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Activation invitations for INTERNAL users, as opposed to InvitationService's
 * portal invitations for customer contacts.
 *
 * Separate from InvitationService because the two are gated differently and point
 * at different columns: inviting a colleague is user.manage and sets user_id;
 * inviting a customer contact is invitation.send and sets customer_contact_id.
 * They share the invitation table, the token machinery and ActivationService —
 * which is what "reuse the machinery rather than adding a parallel flow" means.
 */
@Service
public class UserInvitationService implements UserActivationSender {

    private final InvitationRepository invitations;
    private final AppUserRepository users;
    private final AuthorizedQuery authorizedQuery;
    private final EmailSender email;

    public UserInvitationService(InvitationRepository invitations, AppUserRepository users,
                                 AuthorizedQuery authorizedQuery, EmailSender email) {
        this.invitations = invitations;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.email = email;
    }

    /**
     * userId is a foreign id the caller supplies, so it must be resolved through
     * AuthorizedQuery and not with users.findById — the gate above cannot see
     * arguments, and RLS bounds the lookup to the tenant and nothing narrower.
     * Without this a DEPARTMENT-scoped user.manage holder could mint and MAIL an
     * activation invitation for any user in the tenant, which is a live credential
     * sent to an address they do not control.
     *
     * Nothing flagged it because auth sits outside the two packages
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly covers.
     *
     * NoSuchElementException rather than InvalidTokenException now: an out-of-scope
     * user is a 404 like every other out-of-scope record, whereas a 401 would say
     * "that user exists, you just cannot invite them".
     */
    @Override
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public String issueForUser(UUID userId) {
        AppUser user = authorizedQuery.getById(users, AppUser.class,
                PermissionKeys.USER_MANAGE, userId);

        String raw = SecureTokens.generate();

        Invitation invitation = new Invitation();
        invitation.setId(Uuid7.generate());
        invitation.setTenantId(TenantContext.getRequired());
        invitation.setPurpose(InvitationPurpose.ACTIVATION);
        invitation.setUserId(user.getId());
        invitation.setTokenHash(SecureTokens.hash(raw));
        invitation.setExpiresAt(Instant.now().plus(InvitationService.ACTIVATION_TTL));
        invitations.save(invitation);

        email.send(new EmailMessage(user.getEmail(), "Activate your account",
                "Use this token to activate your account: " + raw));

        return raw;
    }

    /**
     * userId here has already been resolved and authorized by the immediate caller
     * (UserAdminService.deactivate, through authorizedQuery.getById) before this is
     * invoked in the same transaction — unlike issueForUser above, this is not a
     * fresh caller-supplied id arriving unchecked. The write itself only ever
     * revokes rows already scoped to the bound tenant (Invitation is
     * TenantScopedEntity, enforced by RLS and the Hibernate filter), and touches
     * nothing outside the one userId the caller already established it may manage.
     *
     * One row in Invitation covers both activation and password-reset tokens
     * (InvitationPurpose distinguishes them, not separate tables), so a single
     * userId-keyed revocation closes both regardless of purpose.
     *
     * Delegates the actual finder call to PendingInvitationRevoker rather than
     * calling InvitationRepository.findByUserIdAnd... here directly — see that
     * class's own javadoc for why: this class's simple name ends in "Service", so
     * a direct call here would (correctly, structurally) trip
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly even
     * though the id is already authorized by this point.
     */
    @Override
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void revokePendingInvitations(UUID userId) {
        new PendingInvitationRevoker(invitations).revoke(userId);
    }
}
