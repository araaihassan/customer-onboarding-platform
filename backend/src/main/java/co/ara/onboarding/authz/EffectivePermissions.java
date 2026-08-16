package co.ara.onboarding.authz;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

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
}
