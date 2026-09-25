package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentCaseLink;
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
 * No permission is catalogued against a "document_case_link" resource type;
 * this class exists purely for {@code AuthorizedQuery}'s entity-type dispatch,
 * the same reason {@link DocumentVersionDescriptor} exists.
 *
 * A link carries its OWN case_id column (the linked/target case, V23), but scope
 * resolves through the parent document's HOME case, one hop further than
 * {@link DocumentDescriptor}'s own -- link -> document -> case -- exactly like
 * {@link DocumentVersionDescriptor} and {@link DocumentShareDescriptor}. This is
 * deliberate, not an oversight: managing which cases a document is linked into is
 * an operation on the document, gated by the document's own home case, not by
 * whichever case it is being linked to or from. ASSIGNED likewise resolves
 * through the parent document's own uploaded_by column, not the link's own
 * linked_by.
 */
@Component
public class DocumentCaseLinkDescriptor implements ResourceAuthorizationDescriptor<DocumentCaseLink> {

    @Override public String resourceType() { return "document_case_link"; }

    @Override public Class<DocumentCaseLink> entityType() { return DocumentCaseLink.class; }

    /** Unused -- assignedScope reads the parent document's uploaded_by column directly. Same note as DocumentDescriptor. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<DocumentCaseLink> departmentScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.departmentId() == null) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
            return d.get("caseId").in(caseSub);
        });
    }

    @Override public Specification<DocumentCaseLink> teamScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.teamIds().isEmpty()) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(c.get("owningTeamId").in(ctx.teamIds()));
            return d.get("caseId").in(caseSub);
        });
    }

    /** Personal and not team-mediated, borrowed from the parent document -- same invariant as DocumentDescriptor's own assignedScope. */
    @Override public Specification<DocumentCaseLink> assignedScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> cb.equal(d.get("uploadedBy"), ctx.userId()));
    }

    /**
     * The subquery selects document ids matching the condition (over the
     * document's own case_id, i.e. its home case) and tests documentId against
     * them -- never the link's own case_id. RLS still applies to both the
     * document and (nested) case subqueries' own tables, so a link cannot be
     * reached through a document or case in another tenant.
     */
    private Specification<DocumentCaseLink> viaDocument(DocumentCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var d = subquery.from(Document.class);
            subquery.select(d.get("id")).where(condition.build(root, query, cb, d));
            return root.get("documentId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface DocumentCondition {
        Predicate build(Root<DocumentCaseLink> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Document> d);
    }
}
