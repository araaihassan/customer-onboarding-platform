package co.ara.onboarding.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvitationRepository extends JpaRepository<Invitation, UUID> {

    /** RLS constrains this to the bound tenant, so a token from elsewhere does not exist here. */
    Optional<Invitation> findByTokenHash(String tokenHash);
}
