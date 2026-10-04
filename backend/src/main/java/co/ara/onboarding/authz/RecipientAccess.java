package co.ara.onboarding.authz;

import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 6B spec 7.2: whether ANOTHER user -- a notification recipient, never the caller -- can view a
 * record. Builds that user's context and grants from scratch (no request cache, the ACTIVE join
 * included) and runs exactly the predicate a request would: record scope AND any audience filter.
 * Internal users only; a portal or system recipient is always false (portal notifications are
 * sub-project 7's). Infrastructure the notification pipeline depends on, like AuthorizedQuery.
 *
 * <p>Why nothing here can widen access:
 * <ul>
 *   <li>It never reads the request-scoped {@link AuthorizationService} -- the caller's grants are
 *       irrelevant to the answer, in either direction.</li>
 *   <li>The grant set comes from {@link GrantLookup}, the very query the current-actor path runs,
 *       so a disabled role or a non-ACTIVE user contributes nothing here exactly as it does there.</li>
 *   <li>The predicate is {@link AuthorizationPredicateBuilder}'s own, fail-closed on no grant and
 *       with the audience filter ANDed on even at {@code Scope.ALL}.</li>
 *   <li>The count runs under the bound tenant's RLS, and a recipient from another tenant is not
 *       even resolvable ({@link ActorDirectory} is RLS-scoped too); the tenant equality check is a
 *       second, explicit belt over that.</li>
 *   <li>Every "cannot tell" outcome -- null ids, an unknown user, a non-INTERNAL user, no bound
 *       tenant -- is {@code false}, never an exception and never {@code true}.</li>
 * </ul>
 */
@Component
public class RecipientAccess {

    private final ActorDirectory actors;
    private final GrantLookup grants;
    private final AuthorizationPredicateBuilder predicates;
    private final EntityManager em;

    RecipientAccess(ActorDirectory actors, GrantLookup grants, AuthorizationPredicateBuilder predicates,
                    EntityManager em) {
        this.actors = actors;
        this.grants = grants;
        this.predicates = predicates;
        this.em = em;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean canView(UUID userId, String permissionKey, Class<?> entityType, UUID id) {
        if (userId == null || permissionKey == null || entityType == null || id == null) return false;
        UUID tenant = TenantContext.getOrNull();
        if (tenant == null) return false;
        Optional<AuthContext> ctx = actors.findActor(userId);
        if (ctx.isEmpty() || ctx.get().userType() != UserType.INTERNAL) return false;
        if (!tenant.equals(ctx.get().tenantId())) return false;
        return exists(entityType, permissionKey, ctx.get(), grants.forInternalUser(userId), id);
    }

    private <T> boolean exists(Class<T> type, String permissionKey, AuthContext ctx, EffectivePermissions perms,
                               UUID id) {
        // Short-circuit a denial before building any query: identical outcome to the
        // disjunction the predicate builder would return, without the round trip.
        if (!perms.has(permissionKey)) return false;
        Specification<T> spec = predicates.forPermission(permissionKey, type, ctx, perms);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Long> q = cb.createQuery(Long.class);
        Root<T> root = q.from(type);
        Predicate authorized = spec.toPredicate(root, q, cb);
        // Fail closed: every predicate the builder produces is non-null (ALL is an explicit
        // cb.conjunction()), so a null here can only be a bug -- and must never read as "match all".
        if (authorized == null) return false;
        Predicate byId = cb.equal(root.get("id"), id);
        q.select(cb.count(root)).where(cb.and(authorized, byId));
        return em.createQuery(q).getSingleResult() > 0;
    }
}
