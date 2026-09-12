package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.PlanRevisionItem;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * A plan revision's frozen items have no ownership of their own; they inherit
 * scope from the case via their own denormalised case_id (PlanRevisionItem's own
 * javadoc explains why the column is duplicated rather than reached through
 * planRevisionId -> plan_revision.case_id). The same viaCase shape as
 * PlanRevisionDescriptor, TaskDescriptor, MilestoneDescriptor and
 * CaseAttributeValueDescriptor. It exists because AuthorizedQuery dispatches by
 * entity type -- PlanRevisionItem.class -- not because
 * DescriptorRegistry.validate() demands it.
 */
@Component
public class PlanRevisionItemDescriptor implements ResourceAuthorizationDescriptor<PlanRevisionItem> {

    @Override public String resourceType() { return "plan_revision_item"; }

    @Override public Class<PlanRevisionItem> entityType() { return PlanRevisionItem.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<PlanRevisionItem> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<PlanRevisionItem> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<PlanRevisionItem> assignedScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return c.get("id").in(sub);
        });
    }

    /**
     * The subquery selects case ids matching the condition and tests caseId
     * against them. RLS still applies to the subquery's own table, so a plan
     * revision item cannot be reached through a case in another tenant.
     */
    private Specification<PlanRevisionItem> viaCase(CaseCondition condition) {
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
        Predicate build(Root<PlanRevisionItem> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
