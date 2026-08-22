package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Case participants have no ownership of their own; they inherit scope from the
 * case they belong to, the same viaCase shape MilestoneDescriptor and
 * RequirementDescriptor use. Task 13 needs this the moment CaseService.participants
 * reads case_participant rows directly rather than through the already-authorized
 * Case: case.view is RECORD-scoped, so a viewer without ALL scope would otherwise hit
 * DescriptorRegistry.forEntity(CaseParticipant.class) with nothing registered.
 *
 * assignedRelationships() is unused here (a participant row does not itself carry a
 * relevant "who is this row assigned to" question the way Case/Milestone/Requirement
 * do) but the interface requires it; returning the same set keeps it consistent with
 * its siblings rather than inventing a different answer for no reason.
 */
@Component
public class CaseParticipantDescriptor implements ResourceAuthorizationDescriptor<CaseParticipant> {

    @Override public String resourceType() { return "case_participant"; }

    @Override public Class<CaseParticipant> entityType() { return CaseParticipant.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<CaseParticipant> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<CaseParticipant> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<CaseParticipant> assignedScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"),
                            co.ara.onboarding.journey.ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return c.get("id").in(sub);
        });
    }

    private Specification<CaseParticipant> viaCase(CaseCondition condition) {
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
                jakarta.persistence.criteria.Root<CaseParticipant> root,
                jakarta.persistence.criteria.CriteriaQuery<?> query,
                jakarta.persistence.criteria.CriteriaBuilder cb,
                jakarta.persistence.criteria.Root<Case> c);
    }
}
