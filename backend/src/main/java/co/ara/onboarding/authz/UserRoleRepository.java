package co.ara.onboarding.authz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {

    /** Guards role deletion: a role held by any user is disabled, never deleted. */
    long countByRoleId(UUID roleId);

    List<UserRole> findByUserId(UUID userId);

    /** One query for a whole page of users — see UserRoleDirectory. */
    List<UserRole> findByUserIdIn(Collection<UUID> userIds);
}
