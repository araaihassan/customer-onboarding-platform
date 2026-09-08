package co.ara.onboarding.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place {@link InvitationRepository}'s userId- and contactId-keyed
 * finders are called directly, on purpose.
 *
 * Listed as a named exclusion in
 * AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS, not by avoiding a
 * *Service/*Directory suffix. Sub-project 3A Task 2 rebound that rule from a
 * name-shaped match to one that binds on repository injection, precisely
 * because this class (among others) was invisible to the old rule purely by
 * not being named *Service or *Directory -- an exemption a reviewer of the
 * guard itself could not see. The substantive reason it is excluded, unchanged
 * by the rebind:
 *
 * Both ids this acts on are never a fresh, unchecked caller-supplied value:
 * {@link #revoke}'s only caller, UserInvitationService.revokePendingInvitations,
 * only reaches this with a userId already resolved through AuthorizedQuery in the
 * same transaction; {@link #revokeForContact}'s only caller, auth.InvitationService
 * (implementing customer.ContactInvitationSender), likewise resolves the contact
 * through AuthorizedQuery first. And nothing here is exposed back to a caller for
 * display — these are writes keyed on already-authorized ids, not a read of
 * tenant business data standing in for one, which is what the finder-call rule
 * exists to catch.
 */
final class PendingInvitationRevoker {

    private final InvitationRepository invitations;

    PendingInvitationRevoker(InvitationRepository invitations) {
        this.invitations = invitations;
    }

    /**
     * Revokes every still-redeemable invitation for a user — activation and
     * password-reset alike, since InvitationPurpose is what distinguishes them,
     * not separate tables, so one userId-keyed sweep closes both.
     */
    void revoke(UUID userId) {
        Instant now = Instant.now();
        for (Invitation invitation : invitations.findByUserIdAndAcceptedAtIsNullAndRevokedAtIsNull(userId)) {
            invitation.setRevokedAt(now);
            invitations.save(invitation);
        }
    }

    /**
     * The contact-keyed twin of {@link #revoke}, for a portal invitation issued
     * before the underlying app_user ever existed — a contact invitation is keyed
     * on customerContactId (InvitationService.issue), not userId, precisely
     * because a contact may not have a linked user yet. Used when a contact is
     * retired, so a pending activation cannot still be redeemed afterward.
     */
    void revokeForContact(UUID customerContactId) {
        Instant now = Instant.now();
        for (Invitation invitation :
                invitations.findByCustomerContactIdAndAcceptedAtIsNullAndRevokedAtIsNull(customerContactId)) {
            invitation.setRevokedAt(now);
            invitations.save(invitation);
        }
    }
}
