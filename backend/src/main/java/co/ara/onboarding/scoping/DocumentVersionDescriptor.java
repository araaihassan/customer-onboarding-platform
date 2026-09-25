package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.document.DocumentVersion;
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
 * No permission is catalogued against a "document_version" resource type, and
 * {@code DescriptorRegistry.validate()} never asks for one -- but
 * {@code AuthorizedQuery.findAll}/{@code getById} dispatch by ENTITY TYPE, and a
 * document_version read under document.review (catalogued against resource type
 * "document") would otherwise hit
 * {@code DescriptorRegistry.forEntity(DocumentVersion.class)} with nothing
 * registered. This class exists purely to supply that entry, the same reason
 * {@code TaskChecklistItemDescriptor} and {@code CaseParticipantDescriptor}
 * exist.
 *
 * Every predicate is one hop further than {@link DocumentDescriptor}'s own:
 * version -> document -> case. ASSIGNED resolves through the parent document's
 * own uploaded_by column, identically to DocumentDescriptor's ASSIGNED -- a
 * version borrows its document's personal relationship (the uploader) rather
 * than inventing a second one from its own reviewed_by/uploaded_by columns, the
 * same shape TaskChecklistItemDescriptor uses to borrow its parent task's
 * assignee_id.
 */
@Component
public class DocumentVersionDescriptor implements ResourceAuthorizationDescriptor<DocumentVersion> {

    @Override public String resourceType() { return "document_version"; }

    @Override public Class<DocumentVersion> entityType() { return DocumentVersion.class; }

    /** Unused -- assignedScope reads the parent document's uploaded_by column directly. Same note as DocumentDescriptor. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<DocumentVersion> departmentScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.departmentId() == null) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
            return d.get("caseId").in(caseSub);
        });
    }

    @Override public Specification<DocumentVersion> teamScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> {
            if (ctx.teamIds().isEmpty()) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(c.get("owningTeamId").in(ctx.teamIds()));
            return d.get("caseId").in(caseSub);
        });
    }

    /** Personal and not team-mediated, borrowed from the parent document -- same invariant as DocumentDescriptor's own assignedScope. */
    @Override public Specification<DocumentVersion> assignedScope(AuthContext ctx) {
        return viaDocument((root, query, cb, d) -> cb.equal(d.get("uploadedBy"), ctx.userId()));
    }

    /**
     * The subquery selects document ids matching the condition and tests
     * documentId against them. RLS still applies to both the document and
     * (nested) case subqueries' own tables, so a version cannot be reached
     * through a document or case in another tenant.
     */
    private Specification<DocumentVersion> viaDocument(DocumentCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var d = subquery.from(Document.class);
            subquery.select(d.get("id")).where(condition.build(root, query, cb, d));
            return root.get("documentId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface DocumentCondition {
        Predicate build(Root<DocumentVersion> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Document> d);
    }
}
