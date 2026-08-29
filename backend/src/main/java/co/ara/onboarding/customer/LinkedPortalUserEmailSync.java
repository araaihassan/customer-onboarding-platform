package co.ara.onboarding.customer;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;

import java.util.UUID;

/**
 * Keeps a contact's linked portal account ({@code app_user}) in step with the
 * contact record it belongs to.
 *
 * The portal LOGIN is {@code app_user}, not {@code customer_contact} —
 * LoginService reads {@code app_user.status} and {@code app_user.email}, and
 * ActivationService's duplicate-address check reads {@code app_user.email} too.
 * A contact-side change that stops at {@code customer_contact} is therefore
 * invisible to both: a corrected address leaves the portal login on the old
 * one, and a retired contact whose linked account is never touched can still
 * sign in.
 *
 * Package-private and deliberately NOT a Spring bean — the same shape as
 * auth's {@code PendingInvitationRevoker}: constructed inline wherever
 * {@link CustomerContactService} needs it, fed only ids already resolved off a
 * {@link CustomerContact} that {@code AuthorizedQuery} has already authorized
 * under {@code CONTACT_MANAGE} earlier in the same method, never a fresh
 * caller-supplied value — so no additional scope predicate applies here, the
 * same reasoning that class's own javadoc gives for why it needs no guard
 * exclusion of its own.
 *
 * {@code AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly}
 * binds to the CALLING class's own method bodies, not to what fields it holds,
 * so {@link CustomerContactService} may inject {@link AppUserRepository} to
 * construct this and still never itself call a {@code *Repository} finder —
 * every {@code findById} in this flow lives here instead.
 */
final class LinkedPortalUserEmailSync {

    private final AppUserRepository users;

    LinkedPortalUserEmailSync(AppUserRepository users) {
        this.users = users;
    }

    /**
     * No-op when userId is null: not every contact has a linked portal user yet.
     *
     * Pre-checked against {@code findByTenantIdAndEmailIgnoreCase} before
     * writing, the same style {@code ActivationService.activateContact} already
     * uses for this same index. {@code app_user} is unique on
     * {@code (tenant_id, lower(email))} TENANT-WIDE, while
     * {@code customer_contact}'s own uniqueness (V14) is only PER-CUSTOMER — so
     * a correction that already passed {@code CustomerContactService}'s own
     * duplicate-contact check can still collide with a DIFFERENT customer's
     * already-activated contact. Left unchecked, that collision would reach
     * {@code users.save} with no flush and no translation, surfacing at commit
     * — outside the transaction proxy — as a raw
     * {@code DataIntegrityViolationException}, exactly the failure shape
     * {@code CustomerContactService.save}'s own javadoc explains
     * {@code saveAndFlush} exists to prevent for the sibling constraint.
     */
    void syncEmail(UUID userId, String email) {
        if (userId == null) return;
        users.findById(userId).ifPresent((AppUser user) -> {
            users.findByTenantIdAndEmailIgnoreCase(user.getTenantId(), email)
                    .filter(other -> !other.getId().equals(userId))
                    .ifPresent(other -> {
                        throw new PortalEmailConflictException();
                    });
            user.setEmail(email);
            users.save(user);
        });
    }

    /**
     * Ends portal access for the linked account. LoginService admits only
     * ACTIVE, and AuthorizationService resolves zero permissions for anyone
     * else, so this alone closes both "can still sign in" and "can still act"
     * for a retired contact's linked account — the same status
     * {@code UserAdminService.deactivate} sets for the same reason.
     */
    void deactivate(UUID userId) {
        if (userId == null) return;
        users.findById(userId).ifPresent((AppUser user) -> {
            user.setStatus(UserStatus.DEACTIVATED);
            users.save(user);
        });
    }

    /**
     * The symmetric case to {@link #deactivate}: restores portal access when a
     * retired contact is edited back to ACTIVE, so a mis-clicked retirement is
     * not a one-way door recoverable only by direct SQL against the database —
     * {@code UserAdminService} has no reactivate path of its own, and both of
     * {@code ActivationService}'s own {@code setStatus(ACTIVE)} sites are
     * unreachable for an already-activated contact's account. The password hash
     * {@link #deactivate} never touched, so the original credential still
     * works once status is ACTIVE again.
     */
    void reactivate(UUID userId) {
        if (userId == null) return;
        users.findById(userId).ifPresent((AppUser user) -> {
            user.setStatus(UserStatus.ACTIVE);
            users.save(user);
        });
    }
}
