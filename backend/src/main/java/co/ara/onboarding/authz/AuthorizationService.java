package co.ara.onboarding.authz;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Request-scoped: effective permissions are resolved once per request and never
 * cached across requests, so a role change takes effect on the next request with
 * no stale authority (spec 6.7).
 *
 * The memo field is safe precisely because the bean is request-scoped — the same
 * field on a singleton would hand one user's authority to the next.
 */
@Component
@RequestScope
public class AuthorizationService {

    private final JdbcTemplate jdbc;
    private final AuthContextProvider contextProvider;
    private EffectivePermissions memo;

    public AuthorizationService(JdbcTemplate jdbc, AuthContextProvider contextProvider) {
        this.jdbc = jdbc;
        this.contextProvider = contextProvider;
    }

    public EffectivePermissions effectivePermissions() {
        if (memo != null) return memo;

        UUID userId = contextProvider.principal().userId();
        Map<String, Set<Scope>> byPermission = new HashMap<>();

        // r.enabled = true is part of the join, not a post-filter: a disabled role
        // must contribute nothing the moment it is disabled. No tenant predicate is
        // needed -- RLS constrains all four tables to the bound tenant.
        //
        // u.status = 'ACTIVE' is the same idea applied to the actor, and it is what
        // closes the window an already-issued access token would otherwise leave
        // open. Deactivating a user revokes their refresh family, but the browser is
        // still holding a signed access token good for up to fifteen more minutes,
        // and nothing on the authenticated request path consulted status --
        // LoginService's check was the only one in the codebase, and a live session
        // never reaches it again. Resolving zero permissions here means every gated
        // method denies and every AuthorizedQuery predicate collapses to
        // disjunction, so the deactivated session can neither read nor write on the
        // very next request. That is the same promise already made for a revoked
        // grant: authority is resolved server-side per request, never carried in the
        // token.
        //
        // This is a table name in SQL, not a compile-time dependency on identity --
        // authz must not import identity types, and does not. Deny-by-default falls
        // out of the join: a missing or non-ACTIVE app_user row yields no rows,
        // which is no authority rather than all of it.
        jdbc.query("""
            SELECT rg.permission_key AS k, rg.scope AS s
            FROM user_role ur
            JOIN app_user u ON u.id = ur.user_id AND u.status = 'ACTIVE'
            JOIN role r ON r.id = ur.role_id AND r.enabled = true
            JOIN role_grant rg ON rg.role_id = r.id
            WHERE ur.user_id = ?
            """,
            rs -> {
                String key = rs.getString("k");
                Scope scope = Scope.valueOf(rs.getString("s"));
                // Union across roles. There are no deny grants, so this only ever
                // widens -- no precedence order to get wrong (spec 6.5).
                byPermission.computeIfAbsent(key, k -> EnumSet.noneOf(Scope.class)).add(scope);
            },
            userId);

        // Deep-copy to immutable: Map.copyOf alone would leave the inner EnumSets
        // mutable, so a caller could widen its own authority in place.
        memo = new EffectivePermissions(byPermission.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))));
        return memo;
    }

    public boolean has(String permissionKey) {
        return effectivePermissions().has(permissionKey);
    }
}
