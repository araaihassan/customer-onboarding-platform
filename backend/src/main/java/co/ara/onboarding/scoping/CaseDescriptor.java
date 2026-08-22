package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class CaseDescriptor implements ResourceAuthorizationDescriptor<Case> {

    @Override public String resourceType() { return "onboarding_case"; }
    @Override public Class<Case> entityType() { return Case.class; }

    /**
     * The first descriptor whose ASSIGNED scope actually reads this set. CREATOR is
     * excluded on CustomerDescriptor's reasoning: having once created a case is not an
     * ongoing relationship to it.
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Case> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Case> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    /**
     * An EXISTS over case_participant rather than a column comparison, because a case
     * has several personal relationships at once and only some of them qualify. RLS
     * still applies to the subquery's own table, so a participant row in another tenant
     * cannot reach a case here.
     */
    @Override public Specification<Case> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> {
            var sub = query.subquery(UUID.class);
            var participant = sub.from(CaseParticipant.class);
            sub.select(participant.get("caseId")).where(cb.and(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ParticipantStatus.ACTIVE),
                    participant.get("relationship").in(assignedRelationships())));
            return root.get("id").in(sub);
        };
    }
}
