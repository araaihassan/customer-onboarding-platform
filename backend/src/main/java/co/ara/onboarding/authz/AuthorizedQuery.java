package co.ara.onboarding.authz;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The only sanctioned way to read tenant-owned records. Every read is bound to a
 * permission key, so an endpoint cannot issue an unscoped query (spec 6.8).
 *
 * Scope is applied IN the query rather than by filtering results afterwards. That
 * distinction is the whole point: a post-filter still loads out-of-scope rows, so
 * they can leak through a total count, a page size, or a stack trace.
 *
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly is what
 * stops a service quietly going around this class.
 */
@Component
public class AuthorizedQuery {

    private final AuthorizationPredicateBuilder predicates;

    public AuthorizedQuery(AuthorizationPredicateBuilder predicates) {
        this.predicates = predicates;
    }

    /** {@code extra} is the caller's own filter, ANDed with the scope predicate — never replacing it. */
    public <T> Page<T> findAll(JpaSpecificationExecutor<T> repository, Class<T> entityType,
                               String permissionKey, Specification<T> extra, Pageable pageable) {
        Specification<T> authorized = predicates.forPermission(permissionKey, entityType);
        Specification<T> combined = (extra == null) ? authorized : authorized.and(extra);
        return repository.findAll(combined, pageable);
    }

    /**
     * Throws NoSuchElementException — which maps to 404, never 403 (spec 6.8). An
     * out-of-scope record is indistinguishable from one that does not exist, so a
     * caller cannot probe for the existence of records they may not see.
     */
    public <T> T getById(JpaSpecificationExecutor<T> repository, Class<T> entityType,
                         String permissionKey, UUID id) {
        Specification<T> authorized = predicates.forPermission(permissionKey, entityType);
        Specification<T> byId = (root, query, cb) -> cb.equal(root.get("id"), id);
        return repository.findOne(authorized.and(byId))
                .orElseThrow(() -> new NoSuchElementException("Not found"));
    }

    /**
     * Task 32 fix round 1: the sanctioned home for the codebase's second
     * deliberate authorization bypass -- deliberately living HERE, not loose
     * inside a domain service, so the one class every reviewer already checks
     * for "does this skip AuthorizedQuery" is also where a caller finds the
     * one method that partially does. Bypasses only the SCOPE union
     * (DEPARTMENT/TEAM/ASSIGNED) via {@link
     * AuthorizationPredicateBuilder#forPermissionIgnoringScope} -- the
     * AUDIENCE filter still applies, exactly as {@link #findAll}/{@link
     * #getById}'s own {@code forPermission} call carries it. See that
     * method's own javadoc for why the audience half must never be skipped
     * too (a portal contact's cross-customer disclosure, found and fixed in
     * review round 1 of this same task).
     *
     * <p>Returns a bare {@code long}, never rows -- there is deliberately no
     * {@code findAllIgnoringScope}. A caller of THIS method learns only HOW
     * MANY audience-visible records exist beyond their own record-level
     * scope, never WHICH ones; widening this seam to return actual rows
     * would need its own, separately-argued justification, not an extension
     * of this one.
     *
     * <p><b>Precondition, stated here rather than only one file away in
     * {@code AuthorizationPredicateBuilder#withAudience}'s own javadoc:</b>
     * this method is safe only because {@code entityType} has a registered
     * {@link AudienceFilter} -- true for {@link co.ara.onboarding.document.Document}
     * today, the only caller this method has. On an entity type with no
     * registered filter, the scope-union bypass has nothing left to narrow
     * it, and this degrades to an unnarrowed, tenant-wide count for any
     * holder of {@code permissionKey} at any scope -- not the bounded,
     * audience-narrowed aggregate its own class-level javadoc describes. A
     * future caller must confirm its entity type has a real
     * {@link AudienceFilter} before reaching for this method, not just that
     * it holds some grant of the given permission.
     */
    public <T> long countIgnoringScope(JpaSpecificationExecutor<T> repository, Class<T> entityType,
                                       String permissionKey, Specification<T> extra) {
        Specification<T> authorized = predicates.forPermissionIgnoringScope(permissionKey, entityType);
        Specification<T> combined = (extra == null) ? authorized : authorized.and(extra);
        return repository.count(combined);
    }
}
