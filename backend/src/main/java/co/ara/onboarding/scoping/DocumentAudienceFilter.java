package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AudienceFilter;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.platform.UserType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The first real {@link AudienceFilter} (design spec 6.3/6.4). Targeting binds
 * EVERYONE, ALL-scoped readers included -- that is the entire reason this
 * mechanism exists rather than being folded into {@link DocumentDescriptor}'s
 * scope predicates, which {@code AuthorizationPredicateBuilder} short-circuits
 * past the instant an actor holds {@code Scope.ALL}.
 *
 * Only the internal branch lands in this task. Task 13 adds
 * {@code portalAudience} to this same class for the customer-portal side (spec
 * 6.3's second predicate); until then a PORTAL actor reaching this filter is not
 * yet a real caller, since no portal endpoint exists yet.
 */
@Component
public class DocumentAudienceFilter implements AudienceFilter<Document> {

    @Override
    public Class<Document> entityType() {
        return Document.class;
    }

    @Override
    public Specification<Document> audience(AuthContext ctx, String permissionKey) {
        // document.manage grants the administrative handle, never the bytes.
        // Without this branch an ALL-scoped manage holder could not load a
        // document to RETARGET it, so a document targeted at a department that
        // later empties out would be unreachable and unfixable, permanently
        // (spec 6.4). The content endpoint is gated document.view, which IS
        // narrowed -- so this widens the handle without widening the payload.
        if (PermissionKeys.DOCUMENT_MANAGE.equals(permissionKey)) {
            return (root, query, cb) -> cb.conjunction();
        }
        return ctx.userType() == UserType.PORTAL ? portalAudience(ctx) : internalAudience(ctx);
    }

    private Specification<Document> internalAudience(AuthContext ctx) {
        return (root, query, cb) -> cb.or(
                // Untargeted, or targeted at my own department.
                cb.or(cb.isNull(root.get("targetDepartmentId")),
                      ctx.departmentId() == null
                              ? cb.disjunction()      // fail closed: no department matches no target
                              : cb.equal(root.get("targetDepartmentId"), ctx.departmentId())),
                // An explicit share always widens past targeting.
                sharedWith(root, query, cb, SharePrincipalType.USER, ctx.userId()),
                ctx.departmentId() == null
                        ? cb.disjunction()
                        : sharedWith(root, query, cb, SharePrincipalType.DEPARTMENT, ctx.departmentId()));
    }

    /**
     * Task 13 replaces this with the real portal predicate (spec 6.3's second
     * half). Fails closed in the meantime: no portal endpoint exists yet to reach
     * this branch, and an unimplemented audience must never default to open.
     */
    private Specification<Document> portalAudience(AuthContext ctx) {
        return (root, query, cb) -> cb.disjunction();
    }

    /** An EXISTS subquery over document_share, live grants only (revoked_at IS NULL). */
    private Predicate sharedWith(Root<Document> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                 SharePrincipalType principalType, UUID principalId) {
        if (principalId == null) return cb.disjunction();
        var subquery = query.subquery(UUID.class);
        var share = subquery.from(DocumentShare.class);
        subquery.select(share.get("id")).where(cb.and(
                cb.equal(share.get("documentId"), root.get("id")),
                cb.equal(share.get("principalType"), principalType),
                cb.equal(share.get("principalId"), principalId),
                cb.isNull(share.get("revokedAt"))));
        return cb.exists(subquery);
    }
}
