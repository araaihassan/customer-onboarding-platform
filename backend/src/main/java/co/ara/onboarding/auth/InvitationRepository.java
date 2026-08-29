package co.ara.onboarding.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** RLS constrains this to the bound tenant, so a token from elsewhere does not exist here. */
    Optional<Invitation> findByTokenHash(String tokenHash);

    /**
     * Every still-redeemable invitation for a user, activation and password-reset
     * alike — both purposes live in this one table, so a single userId-keyed finder
     * closes both. Used to revoke pending credentials on deactivation.
     */
    List<Invitation> findByUserIdAndAcceptedAtIsNullAndRevokedAtIsNull(UUID userId);
}
