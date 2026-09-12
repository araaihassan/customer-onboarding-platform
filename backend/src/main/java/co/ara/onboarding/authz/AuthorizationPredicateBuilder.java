package co.ara.onboarding.authz;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Objects;
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
    private final AudienceRegistry audiences;

    public AuthorizationPredicateBuilder(AuthorizationService authorization,
                                         AuthContextProvider contextProvider,
                                         DescriptorRegistry registry,
                                         AudienceRegistry audiences) {
        this.authorization = authorization;
        this.contextProvider = contextProvider;
        this.registry = registry;
        this.audiences = audiences;
    }

    public <T> Specification<T> forPermission(String permissionKey, Class<T> entityType) {
        Set<Scope> scopes = authorization.effectivePermissions().scopesFor(permissionKey);

        // Fail closed: no grant means no rows, never all rows. Returns BEFORE the
        // audience lookup -- there is nothing to narrow, and a filter must never
        // be able to widen a denial.
        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();

        AuthContext ctx = contextProvider.current();
        Specification<T> scopePredicate = scopePredicate(scopes, entityType, ctx);

        // The audience is ANDed AFTER the scope union, and deliberately also in
        // the ALL case -- that is the whole mechanism (spec 6.2). Absent for every
        // entity that declares no filter, which is all of them but Document.
        return audiences.forEntity(entityType)
                .<Specification<T>>map(f -> scopePredicate.and(requireNonNullAudience(f, ctx, permissionKey, entityType)))
                .orElse(scopePredicate);
    }

    /**
     * Specification.and(null) silently returns just the left-hand predicate --
     * exactly the fail-OPEN shape AudienceFilter's own javadoc forbids ("must
     * return cb.disjunction() to fail closed, never null"). Nothing implements
     * AudienceFilter yet that returns null, so there is no live bug today, but
     * Tasks 12/13 write the first real one against a javadoc-only contract; this
     * turns a silently-widened read into a loud NullPointerException naming the
     * offending entity type instead.
     */
    private <T> Specification<T> requireNonNullAudience(AudienceFilter<T> filter, AuthContext ctx,
                                                        String permissionKey, Class<T> entityType) {
        return Objects.requireNonNull(filter.audience(ctx, permissionKey),
                "AudienceFilter for " + entityType.getSimpleName() + " returned null; "
                        + "must return cb.disjunction() to fail closed, never null");
    }

    private <T> Specification<T> scopePredicate(Set<Scope> scopes, Class<T> entityType, AuthContext ctx) {
        // ALL subsumes the others; short-circuit to an unconditional match rather
        // than OR-ing a match-all with narrower predicates. Note this no longer
        // returns from forPermission -- the audience still applies on top.
        if (scopes.contains(Scope.ALL)) return (root, query, cb) -> cb.conjunction();

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
