package co.ara.onboarding.authz;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Which roles each user holds, in bulk.
 *
 * A directory rather than a *Service, and deliberately so — the same shape as
 * ActorDirectory in the opposite direction. Its callers are already gated: it is
 * read only from UserAdminService, whose every public method carries
 * {@code @RequirePermission}, and it answers a question about users rather than
 * about the authorization model. Gating it independently would mean choosing one
 * permission for both the user.view read path and the user.manage write path, and
 * a role holding only user.manage would then fail on the write.
 *
 * Bulk, because the alternative is one query per row of the user list. Isolation
 * is user_role's RLS policy plus the tenant GUC bound by the enclosing
 * transaction, which is what {@code @Transactional} here guarantees is open.
 */
@Component
public class UserRoleDirectory {

    private final UserRoleRepository userRoles;

    public UserRoleDirectory(UserRoleRepository userRoles) { this.userRoles = userRoles; }

    /** Users holding no role are absent from the map, not mapped to an empty set. */
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> roleIdsByUser(Collection<UUID> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<UserRole> assignments = userRoles.findByUserIdIn(List.copyOf(userIds));
        return assignments.stream().collect(Collectors.groupingBy(
                UserRole::getUserId,
                Collectors.mapping(UserRole::getRoleId, Collectors.toUnmodifiableSet())));
    }
}
