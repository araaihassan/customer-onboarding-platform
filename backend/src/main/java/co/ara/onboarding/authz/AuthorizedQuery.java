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
}
