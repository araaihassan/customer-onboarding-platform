package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.sla.SlaClock;
import co.ara.onboarding.sla.SlaPause;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * An sla_pause inherits scope from its clock, which inherits it from its case -- the
 * CaseParticipantDescriptor shape one level deeper (pause -> clock -> case). Fails closed
 * identically: no department or no teams yields a disjunction.
 */
@Component
public class SlaPauseDescriptor implements ResourceAuthorizationDescriptor<SlaPause> {

    @Override public String resourceType() { return "sla_pause"; }

    @Override public Class<SlaPause> entityType() { return SlaPause.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<SlaPause> departmentScope(AuthContext ctx) {
        return viaCase((query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<SlaPause> teamScope(AuthContext ctx) {
        return viaCase((query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<SlaPause> assignedScope(AuthContext ctx) {
        return viaCase((query, cb, c) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return c.get("id").in(sub);
        });
    }

    private Specification<SlaPause> viaCase(CaseCondition condition) {
        return (root, query, cb) -> {
            var clocks = query.subquery(UUID.class);
            var clock = clocks.from(SlaClock.class);
            var cases = query.subquery(UUID.class);
            var c = cases.from(Case.class);
            cases.select(c.get("id")).where(condition.build(query, cb, c));
            clocks.select(clock.get("id")).where(clock.get("caseId").in(cases));
            return root.get("clockId").in(clocks);
        };
    }

    @FunctionalInterface
    private interface CaseCondition {
        Predicate build(CriteriaQuery<?> query, CriteriaBuilder cb, Root<Case> c);
    }
}
