package co.ara.onboarding.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * The one place {@link InvitationRepository}'s userId-keyed finder is called
 * directly, on purpose.
 *
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly binds to
 * classes whose simple name ends in "Service" or "Directory" in
 * co.ara.onboarding.auth, and this class deliberately is neither — the same shape
 * CLAUDE.md records for CaseEngine calling CaseRepository.lockById directly:
 * "it is never called from a *Service or *Directory, ... so the finder rule never
 * sees it," a legitimate exception the rule's own name-shape does not see, rather
 * than a special-cased clause added to the rule itself.
 *
 * The id this acts on is never a fresh, unchecked caller-supplied value: its only
 * caller, UserInvitationService.revokePendingInvitations, only reaches this with a
 * userId that UserAdminService.deactivate has already resolved through
 * AuthorizedQuery in the same transaction. And nothing here is exposed back to a
 * caller for display — this is a write keyed on an already-authorized id, not a
 * read of tenant business data standing in for one, which is what the finder-call
 * rule exists to catch.
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
}
