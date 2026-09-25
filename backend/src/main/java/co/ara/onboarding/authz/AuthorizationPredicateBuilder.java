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
        return withAudience(scopePredicate, entityType, ctx, permissionKey);
    }

    /**
     * Task 32 fix round 1: bypasses ONLY the record-level SCOPE union
     * (DEPARTMENT/TEAM/ASSIGNED) -- an unconditional {@code cb.conjunction()}
     * stands in for {@link #scopePredicate}, never called at all -- while the
     * AUDIENCE filter still applies exactly as {@link #forPermission}'s own
     * does, ANDed on top of that unconditional match. Still fails closed
     * identically to {@link #forPermission} on {@code scopes.isEmpty()}: a
     * caller holding no grant at all gets {@code disjunction()} here too,
     * never an unscoped {@code conjunction()} -- this bypasses the SCOPE
     * union for a caller who already holds SOME grant, never authorization
     * itself.
     *
     * <p>This exists for exactly one caller today,
     * {@code authz.AuthorizedQuery#countIgnoringScope}, itself used by
     * {@code document.DocumentService#visibilitySummary}'s deliberately
     * scope-unbounded {@code total} aggregate (see that method's own javadoc
     * for the full argument). A security review of the first version of that
     * method (which bypassed BOTH halves, calling the repository directly)
     * found this exact gap live: a portal contact holds {@code document.view}
     * at {@code Scope.ALL} ({@code authz.PortalPermissions}' own javadoc
     * states, in capitals, that this is safe ONLY because
     * {@code scoping.DocumentAudienceFilter} narrows every other read), and
     * the fully-bypassed version's {@code total} skipped that narrowing too --
     * so a portal contact of customer A could read a count that included
     * customer B's documents, a same-tenant, cross-CUSTOMER disclosure RLS
     * does nothing to stop (RLS is tenant isolation, not audience narrowing).
     * Keeping the audience filter ANDed on here is what collapses a portal
     * actor's own bypassed count back down to their own customer, and an
     * internal ALL-scoped reader's back down to documents actually targeted
     * at them (design spec §10 invariant 5: "the audience filter binds ALL").
     */
    public <T> Specification<T> forPermissionIgnoringScope(String permissionKey, Class<T> entityType) {
        Set<Scope> scopes = authorization.effectivePermissions().scopesFor(permissionKey);

        if (scopes.isEmpty()) return (root, query, cb) -> cb.disjunction();

        AuthContext ctx = contextProvider.current();
        Specification<T> unconditional = (root, query, cb) -> cb.conjunction();
        return withAudience(unconditional, entityType, ctx, permissionKey);
    }

    /**
     * The audience-composition tail shared by {@link #forPermission} and
     * {@link #forPermissionIgnoringScope} -- ANDed AFTER the scope predicate
     * (whichever the caller passes in), and deliberately also in the ALL
     * case -- that is the whole mechanism (spec 6.2). Absent for every entity
     * that declares no filter, which is all of them but {@code Document}.
     */
    private <T> Specification<T> withAudience(Specification<T> scopePredicate, Class<T> entityType,
                                              AuthContext ctx, String permissionKey) {
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
