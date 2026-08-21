package co.ara.onboarding.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JpaSpecificationExecutor is required, not incidental: reads go through
 * AuthorizedQuery, which composes the scope predicate as a Specification. A
 * repository without it can only be queried in ways that bypass scope.
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID>, JpaSpecificationExecutor<AppUser> {
    Optional<AppUser> findByTenantIdAndEmailIgnoreCase(UUID tenantId, String email);

    @Query("SELECT u FROM AppUser u WHERE :teamId MEMBER OF u.teamIds")
    List<AppUser> findByTeamId(@Param("teamId") UUID teamId);
}
