package co.ara.onboarding.authz;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What one user may do, resolved for one request: the union of every grant across
 * their enabled roles.
 *
 * An ungranted permission yields an empty set rather than null or an exception —
 * absence of a grant is the denial (spec 6.5), so every caller reads "not
 * granted" the same way without a special case to forget.
 */
public record EffectivePermissions(Map<String, Set<Scope>> byPermission) {

    public Set<Scope> scopesFor(String permissionKey) {
        return byPermission.getOrDefault(permissionKey, Collections.emptySet());
    }

    public boolean has(String permissionKey) {
        return !scopesFor(permissionKey).isEmpty();
    }

    /** No permissions at all -- a deactivated actor, or a portal user with no linked contact. */
    public static EffectivePermissions none() {
        return new EffectivePermissions(Map.of());
    }

    /**
     * Wraps a fixed, single-scope-per-key set -- PortalPermissions' own return
     * shape -- into the Set-valued form the rest of this class works with. A
     * portal actor's authority is a code constant rather than a union of role
     * grants, so there is never more than one scope per key to begin with.
     */
    public static EffectivePermissions of(Map<String, Scope> singleScopePerKey) {
        return new EffectivePermissions(singleScopePerKey.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.of(e.getValue()))));
    }
}
