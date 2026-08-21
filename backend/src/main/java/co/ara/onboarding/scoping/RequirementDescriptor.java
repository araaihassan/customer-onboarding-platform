package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import co.ara.onboarding.journey.Requirement;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Requirements have no ownership of their own; they inherit scope from the case
 * they belong to. Every scope therefore resolves through a subquery over Case
 * rather than over requirement's own columns -- the same viaCustomer shape
 * CustomerContactDescriptor uses, walking up to onboarding_case instead. Note
 * there is deliberately no requirement.satisfy permission (satisfying a
 * requirement gates on milestone.complete), so this descriptor exists only to
 * satisfy requirement.waive.
 */
@Component
public class RequirementDescriptor implements ResourceAuthorizationDescriptor<Requirement> {

    @Override public String resourceType() { return "requirement"; }

    @Override public Class<Requirement> entityType() { return Requirement.class; }

    /** Same relationships as CaseDescriptor: ASSIGNED here is a personal relationship to the parent case. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Requirement> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<Requirement> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<Requirement> assignedScope(AuthContext ctx) {
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
     * The subquery selects case ids matching the condition and tests caseId against
     * them. RLS still applies to the subquery's own table, so a requirement cannot be
     * reached through a case in another tenant.
     */
    private Specification<Requirement> viaCase(CaseCondition condition) {
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
        Predicate build(Root<Requirement> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
