package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);
}
