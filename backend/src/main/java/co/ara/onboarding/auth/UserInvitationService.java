package co.ara.onboarding.auth;

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
    private final EmailSender email;

    public UserInvitationService(InvitationRepository invitations, AppUserRepository users,
                                 EmailSender email) {
        this.invitations = invitations;
        this.users = users;
        this.email = email;
    }

    @Override
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public String issueForUser(UUID userId) {
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new InvalidTokenException("No such user"));

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
}
