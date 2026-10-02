package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.sla.Escalation;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/** Escalation rows inherit scope from their case (sub-project 6 Task 7), the viaCase shape CaseParticipantDescriptor uses. Fails closed. */
@Component
public class EscalationDescriptor implements ResourceAuthorizationDescriptor<Escalation> {

    @Override public String resourceType() { return "escalation"; }

    @Override public Class<Escalation> entityType() { return Escalation.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Escalation> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<Escalation> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<Escalation> assignedScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(co.ara.onboarding.journey.CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"),
                            co.ara.onboarding.journey.ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return c.get("id").in(sub);
        });
    }

    private Specification<Escalation> viaCase(CaseCondition condition) {
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
        jakarta.persistence.criteria.Predicate build(
                jakarta.persistence.criteria.Root<Escalation> root,
                jakarta.persistence.criteria.CriteriaQuery<?> query,
                jakarta.persistence.criteria.CriteriaBuilder cb,
                jakarta.persistence.criteria.Root<Case> c);
    }
}
