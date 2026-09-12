package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.PlanRevision;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Gate 2 of QA Q22: plan.issue and plan.approve_schedule are both catalogued
 * RECORD-scoped on onboarding_case (unlike gate 1's plan.approve_shape, ALL-only
 * with no record to scope against), so this descriptor delegates via the
 * denormalised case_id the same viaCase shape as TaskDescriptor,
 * MilestoneDescriptor and CaseAttributeValueDescriptor: DEPARTMENT reads the
 * case's owningDepartmentId, TEAM its owningTeamId, ASSIGNED its
 * case_participant rows. It exists because AuthorizedQuery dispatches by entity
 * type -- PlanRevision.class -- not because DescriptorRegistry.validate() demands
 * it (onboarding_case already has CaseDescriptor); the same reason
 * CaseParticipantDescriptor and CaseAttributeValueDescriptor exist ahead of
 * validate() ever requiring them.
 */
@Component
public class PlanRevisionDescriptor implements ResourceAuthorizationDescriptor<PlanRevision> {

    @Override public String resourceType() { return "plan_revision"; }

    @Override public Class<PlanRevision> entityType() { return PlanRevision.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<PlanRevision> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<PlanRevision> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<PlanRevision> assignedScope(AuthContext ctx) {
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
     * revision cannot be reached through a case in another tenant.
     */
    private Specification<PlanRevision> viaCase(CaseCondition condition) {
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
        Predicate build(Root<PlanRevision> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
