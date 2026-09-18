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

    /**
     * Task 20 review finding, now closed: this used to resolve ONLY through
     * the document's HOME case ({@link #viaCase}), so a DEPARTMENT-scoped
     * reader whose department owned only the case a document is LINKED
     * into -- not its home case -- could not see it at all, even though
     * {@code DocumentService.forCase}'s own {@code homeOrLinked} filter
     * would otherwise match: {@code AuthorizationPredicateBuilder.forPermission}
     * ANDs the scope predicate with that filter, and the scope predicate
     * alone still rejected it. Widened to OR in a second path
     * ({@link #viaLinkedCase}) through a LIVE {@code document_case_link}
     * row, using the identical condition lambda as {@link #viaCase} so both
     * paths test the exact same department/team match.
     */
    @Override public Specification<Document> departmentScope(AuthContext ctx) {
        CaseCondition condition = (root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId());
        return viaCase(condition).or(viaLinkedCase(condition));
    }

    /** Same widening as {@link #departmentScope}, same reasoning -- see its own javadoc. */
    @Override public Specification<Document> teamScope(AuthContext ctx) {
        CaseCondition condition = (root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds());
        return viaCase(condition).or(viaLinkedCase(condition));
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

    /**
     * One level deeper than {@link #viaCase}: matches a document that is
     * LINKED (via a live {@code document_case_link} row) into ANY case
     * satisfying {@code condition}, not just its own home case. The inner
     * subquery selects case ids matching the condition (mirroring
     * {@link #viaCase} exactly); the middle subquery selects the document
     * ids of every LIVE link whose target case is one of those; the outer
     * predicate tests this document's own id against that set. RLS still
     * applies to every nested table, so a document cannot be reached through
     * a link, or a case, in another tenant.
     *
     * <p>Like every scope predicate in this codebase, this one is
     * permission-agnostic: {@code AuthorizationPredicateBuilder.forPermission}
     * applies it to EVERY permission catalogued against {@code Document}
     * ({@code document.view}, {@code document.manage}, {@code document.upload},
     * {@code document.share} alike), not just the read path this widening was
     * added for. That is safe only because every write against a document
     * independently re-resolves its own ids under its own permission via
     * {@link co.ara.onboarding.authz.AuthorizedQuery} first (CLAUDE.md's
     * standing write-path invariant), never relying on scope alone -- a
     * future write path must keep doing that re-resolution rather than
     * leaning on this (or any) descriptor's scope predicate by itself.
     */
    private Specification<Document> viaLinkedCase(CaseCondition condition) {
        return (root, query, cb) -> {
            var linkSub = query.subquery(UUID.class);
            var link = linkSub.from(DocumentCaseLink.class);

            var caseSub = linkSub.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(condition.build(root, query, cb, c));

            linkSub.select(link.get("documentId"))
                   .where(cb.and(cb.isNull(link.get("revokedAt")), link.get("caseId").in(caseSub)));

            return root.get("id").in(linkSub);
        };
    }

    @FunctionalInterface
    private interface CaseCondition {
        Predicate build(Root<Document> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
