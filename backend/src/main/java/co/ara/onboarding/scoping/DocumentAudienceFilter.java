package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AudienceFilter;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.PortalContactDirectory;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.document.SharePrincipalType;
import co.ara.onboarding.document.VisibilityTier;
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
 * Task 12 built the internal branch; Task 13 adds {@code portalAudience} to this
 * same class for the customer-portal side (spec 6.3's second predicate), resolving
 * the acting contact through {@link PortalContactDirectory} -- the same port
 * {@code AuthorizationService} uses to pick between {@code PortalPermissions}'
 * forContact()/forSponsor(), so there is exactly one definition of "an active
 * contact for this user" in the codebase, not two that can drift apart.
 */
@Component
public class DocumentAudienceFilter implements AudienceFilter<Document> {

    private final PortalContactDirectory contacts;

    public DocumentAudienceFilter(PortalContactDirectory contacts) {
        this.contacts = contacts;
    }

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
        // Every key but document.manage -- document.view, and also
        // document.share/document.review/document.upload/document.request, none
        // of which spec 6.4 rules on individually -- is narrowed by this same
        // audience. That is a deliberate safe default, not an oversight, and it
        // has a real consequence: Task 19's share() and Task 27's review() will
        // both 404 on a Legal-targeted document for anyone outside Legal,
        // including an ALL-scoped Administrator. The recovery path is the one
        // spec 6.4 already names -- retarget via document.manage first, then act.
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
     * Q9's three tiers govern the CUSTOMER side -- "each contact sees only their
     * own", "company level attachments: visible to all in company", "sensitive
     * documents: restricted even within the company unless explicitly shared".
     * They are not internal-staff restrictions; internalAudience() handles those.
     *
     * FAILS CLOSED at every step: an actor with no live contact row matches nothing,
     * which is what makes contact retirement take effect on the very next request.
     */
    private Specification<Document> portalAudience(AuthContext ctx) {
        return (root, query, cb) -> {
            var contact = contacts.findActiveContactForUser(ctx.userId()).orElse(null);
            if (contact == null) {
                return cb.disjunction();   // no live contact => no documents, ever
            }

            Predicate atMyCustomer = cb.equal(root.get("customerId"), contact.customerId());

            Predicate byTier = cb.or(
                    // COMPANY_SHARED: every ACTIVE contact at this customer. The
                    // contact's own ACTIVE status is already proven by the lookup above.
                    cb.equal(root.get("visibilityTier"), VisibilityTier.COMPANY_SHARED),
                    // CONTACT_ONLY: the owning contact alone.
                    cb.and(cb.equal(root.get("visibilityTier"), VisibilityTier.CONTACT_ONLY),
                           cb.equal(root.get("ownerContactId"), contact.id())));
            // SENSITIVE appears in neither disjunct: it reaches nobody BY TIER, and
            // only ever through the explicit share below. That absence is the rule.

            Predicate byLabel = cb.or(
                    cb.isNull(root.get("targetContactLabel")),
                    contact.label() == null
                            ? cb.disjunction()   // an unlabelled contact matches no label target
                            : cb.equal(root.get("targetContactLabel"), contact.label()));

            // The share disjunct is ORed against the WHOLE of the above, never folded
            // into byTier. That is precisely Q9's "restricted ... unless explicitly
            // shared": a share overrides tier AND label together.
            return cb.or(
                    cb.and(atMyCustomer, byTier, byLabel),
                    sharedWith(root, query, cb, SharePrincipalType.CONTACT, contact.id()));
        };
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
