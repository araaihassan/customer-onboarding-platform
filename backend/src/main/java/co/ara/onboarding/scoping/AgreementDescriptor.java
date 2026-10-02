package co.ara.onboarding.scoping;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseParticipant;
import co.ara.onboarding.journey.ParticipantStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * {@code agreement}'s own resourceType (design spec 6.2), the descriptor
 * {@code DescriptorRegistry.validate()} refuses to start without -- Task 5 catalogues
 * all four agreement permissions with resourceType "agreement" and every one allows
 * a record scope, so this had to land in the same commit as that catalogue change.
 *
 * {@code agreement} carries its own case_id column directly (V25__agreement.sql), so
 * this is a single-hop viaCase, exactly {@link DocumentRequestDescriptor}'s shape --
 * with the entity type swapped and nothing else changed. It has no personal
 * relationship column of its own the way a task's assignee_id or a document's
 * uploaded_by does (owner_user_id is the drafting owner, not an ongoing personal
 * relationship any more than a case's own creator is -- CaseDescriptor's reasoning),
 * so ASSIGNED resolves through case_participant, the same fallback
 * DocumentRequestDescriptor, ApprovalDescriptor and CaseAttributeValueDescriptor use.
 */
@Component
public class AgreementDescriptor implements ResourceAuthorizationDescriptor<Agreement> {

    @Override public String resourceType() { return "agreement"; }

    @Override public Class<Agreement> entityType() { return Agreement.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Agreement> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<Agreement> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<Agreement> assignedScope(AuthContext ctx) {
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
     * against them. RLS still applies to the subquery's own table, so an agreement
     * cannot be reached through a case in another tenant.
     */
    private Specification<Agreement> viaCase(CaseCondition condition) {
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
        Predicate build(Root<Agreement> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
