package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.document.Document;
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
 * Documents inherit DEPARTMENT and TEAM from the case they belong to, the same
 * viaCase shape TaskDescriptor and MilestoneDescriptor already use. ASSIGNED is
 * deliberately different from the case-participant-mediated shape those two
 * siblings share for their own ASSIGNED: it resolves through the document's own
 * uploaded_by column alone -- a personal relationship (the RelationshipType
 * invariant CLAUDE.md states: "ASSIGNED means a personal relationship... access
 * mediated by a team the user belongs to is TEAM"), never team-mediated, exactly
 * the way TaskDescriptor's ASSIGNED reads assignee_id rather than
 * case_participant rows.
 */
@Component
public class DocumentDescriptor implements ResourceAuthorizationDescriptor<Document> {

    @Override public String resourceType() { return "document"; }

    @Override public Class<Document> entityType() { return Document.class; }

    /**
     * Unused here -- assignedScope reads the document's own uploaded_by column
     * instead of case_participant relationships -- but the interface requires an
     * answer. Returning the same set every case-scoped sibling does keeps this
     * consistent rather than inventing a different one for no reason
     * (TaskDescriptor carries the identical note).
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Document> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<Document> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    /**
     * Personal and not team-mediated: a document uploaded by a fellow team
     * member, but not by this actor, must not match. Comparing uploaded_by
     * directly also fails closed for ctx.userId() == null, which never happens in
     * practice but costs nothing to note -- uploaded_by is NOT NULL, so it can
     * never equal a null actor id either.
     */
    @Override public Specification<Document> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.equal(root.get("uploadedBy"), ctx.userId());
    }

    /**
     * The subquery selects case ids matching the condition and tests caseId
     * against them. RLS still applies to the subquery's own table, so a document
     * cannot be reached through a case in another tenant.
     */
    private Specification<Document> viaCase(CaseCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var c = subquery.from(Case.class);
            subquery.select(c.get("id"))
                    .where(condition.build(root, query, cb, c));
            return root.get("caseId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface CaseCondition {
        Predicate build(Root<Document> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
