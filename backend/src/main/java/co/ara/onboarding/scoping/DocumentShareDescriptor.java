package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentShare;
import co.ara.onboarding.journey.Case;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * No permission is catalogued against a "document_share" resource type; this
 * class exists purely for {@code AuthorizedQuery}'s entity-type dispatch, the
 * same reason {@link DocumentVersionDescriptor} exists (see its own javadoc for
 * the full reasoning).
 *
 * Every predicate is one hop further than {@link DocumentDescriptor}'s own:
 * share -> document -> case. ASSIGNED resolves through the parent document's own
 * uploaded_by column, not the share's own granted_by -- a share borrows its
 * document's personal relationship rather than inventing a second one, the same
 * shape {@link DocumentVersionDescriptor} uses.
 */
@Component
public class DocumentShareDescriptor implements ResourceAuthorizationDescriptor<DocumentShare> {

    @Override public String resourceType() { return "document_share"; }

    @Override public Class<DocumentShare> entityType() { return DocumentShare.class; }

    /** Unused -- assignedScope reads the parent document's uploaded_by column directly. Same note as DocumentDescriptor. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<DocumentShare> departmentScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.departmentId() == null) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
            return d.get("caseId").in(caseSub);
        });
    }

    @Override public Specification<DocumentShare> teamScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.teamIds().isEmpty()) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(c.get("owningTeamId").in(ctx.teamIds()));
            return d.get("caseId").in(caseSub);
        });
    }

    /** Personal and not team-mediated, borrowed from the parent document -- same invariant as DocumentDescriptor's own assignedScope. */
    @Override public Specification<DocumentShare> assignedScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> cb.equal(d.get("uploadedBy"), ctx.userId()));
    }

    /**
     * The subquery selects document ids matching the condition and tests
     * documentId against them. RLS still applies to both the document and
     * (nested) case subqueries' own tables, so a share cannot be reached through
     * a document or case in another tenant.
     */
    private Specification<DocumentShare> viaDocument(DocumentCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var d = subquery.from(Document.class);
            subquery.select(d.get("id")).where(condition.build(root, query, cb, d));
            return root.get("documentId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface DocumentCondition {
        Predicate build(Root<DocumentShare> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Document> d);
    }
}
