package co.ara.onboarding.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /**
     * RLS constrains this to the bound tenant, so a token issued under another
     * tenant simply does not exist here — which is what makes a stolen cookie
     * useless against a different tenant's path.
     */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyId(UUID familyId);
}
