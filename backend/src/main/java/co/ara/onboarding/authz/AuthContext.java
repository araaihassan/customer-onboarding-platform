package co.ara.onboarding.authz;

import co.ara.onboarding.platform.UserType;

import java.util.Set;
import java.util.UUID;

/**
 * Who is acting, in the terms authorization needs: enough to resolve every scope
 * without a further database lookup.
 *
 * userType is {@code platform.UserType}, not an identity type. authz must not
 * depend on identity — identity gains a permission-gated service in Task 21, so
 * that edge runs the other way — and platform is the module both may depend on.
 *
 * teamIds is a Set because TEAM scope is "any team the actor belongs to", and
 * departmentId is nullable because a user need not have one. Both nullable/empty
 * cases must resolve to a predicate that matches nothing; see the descriptors.
 */
public record AuthContext(UUID tenantId, UUID userId, UserType userType,
                          UUID departmentId, Set<UUID> teamIds) {}
