package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Turns a permission key into a query predicate for one entity type. This is what
 * endpoints cannot bypass: scope is applied in the query, not filtered afterwards,
 * so an out-of-scope record is never loaded and cannot leak through a count, a
 * page total, or an error message.
 */
@Component
public class AuthorizationPredicateBuilder {

    private final AuthorizationService authorization;
    private final AuthContextProvider contextProvider;
    private final DescriptorRegistry registry;

    public AuthorizationPredicateBuilder(AuthorizationService authorization,
                                         AuthContextProvider contextProvider,
                                         DescriptorRegistry registry) {
        this.authorization = authorization;
        this.contextProvider = contextProvider;
        this.registry = registry;
    }

    public <T> Specification<T> forPermission(String permissionKey, Class<T> entityType) {
        Set<Scope> scopes = authorization.effectivePermissions().scopesFor(permissionKey);

        // Fail closed: no grant means no rows, never all rows.
        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();

        // ALL subsumes the others; short-circuit to an unconditional match rather
        // than OR-ing a match-all with narrower predicates.
        if (scopes.contains(Scope.ALL)) return (root, query, cb) -> cb.conjunction();

        AuthContext ctx = contextProvider.current();
        ResourceAuthorizationDescriptor<T> descriptor = registry.forEntity(entityType);

        Specification<T> combined = null;
        for (Scope scope : scopes) {
            Specification<T> part = switch (scope) {
                case DEPARTMENT -> descriptor.departmentScope(ctx);
                case TEAM       -> descriptor.teamScope(ctx);
                case ASSIGNED   -> descriptor.assignedScope(ctx);
                case ALL        -> null;   // unreachable, handled above
            };
            if (part == null) continue;
            // Scopes are SETS, not a hierarchy: union them (spec 6.3). A record
            // personally owned by the actor but belonging to someone else's team
            // qualifies under ASSIGNED even though TEAM excludes it.
            combined = (combined == null) ? part : combined.or(part);
        }
        return combined == null ? (root, query, cb) -> cb.disjunction() : combined;
    }
}
