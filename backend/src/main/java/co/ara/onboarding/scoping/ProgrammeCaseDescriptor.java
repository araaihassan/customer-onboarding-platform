package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.programme.Programme;
import co.ara.onboarding.programme.ProgrammeCase;
import co.ara.onboarding.programme.ProgrammeParticipant;
import co.ara.onboarding.programme.ProgrammeParticipantStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * A programme's case-membership rows have no ownership of their own; they
 * inherit scope from the programme they belong to, the same viaCase shape
 * CaseParticipantDescriptor uses for cases -- here viaProgramme, keyed on the
 * denormalised programme_id. No permission is catalogued against a
 * "programme_case" resource type and DescriptorRegistry.validate() never asks
 * for one, but AuthorizedQuery.findAll/getById dispatch by ENTITY TYPE, so
 * ProgrammeService reading these rows under programme.view/programme.manage
 * would otherwise hit DescriptorRegistry.forEntity(ProgrammeCase.class) with
 * nothing registered -- the exact trap CaseParticipantDescriptor and
 * CaseAttributeValueDescriptor exist to close.
 *
 * assignedScope resolves through programme_participant on the actor, exactly
 * like ProgrammeDescriptor's own -- it must NOT reach into onboarding_case or
 * case_participant. A programme_case row records only which cases belong to the
 * programme; it grants no journey access of its own, and neither does this
 * descriptor.
 */
@Component
public class ProgrammeCaseDescriptor implements ResourceAuthorizationDescriptor<ProgrammeCase> {

    @Override public String resourceType() { return "programme_case"; }

    @Override public Class<ProgrammeCase> entityType() { return ProgrammeCase.class; }

    /** Unused -- assignedScope reads programme_participant directly. Same note as ProgrammeDescriptor. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.PARTICIPANT);
    }

    @Override public Specification<ProgrammeCase> departmentScope(AuthContext ctx) {
        return viaProgramme((root, query, cb, p) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(p.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<ProgrammeCase> teamScope(AuthContext ctx) {
        return viaProgramme((root, query, cb, p) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : p.get("owningTeamId").in(ctx.teamIds()));
    }

    @Override public Specification<ProgrammeCase> assignedScope(AuthContext ctx) {
        return viaProgramme((root, query, cb, p) -> {
            if (ctx.userId() == null) return cb.disjunction();
            var sub = query.subquery(UUID.class);
            var participant = sub.from(ProgrammeParticipant.class);
            sub.select(participant.get("programmeId")).where(
                    cb.equal(participant.get("userId"), ctx.userId()),
                    cb.equal(participant.get("status"), ProgrammeParticipantStatus.ACTIVE));
            return p.get("id").in(sub);
        });
    }

    /**
     * The subquery selects programme ids matching the condition and tests
     * programmeId against them. RLS still applies to the subquery's own table, so
     * a membership row cannot be reached through a programme in another tenant.
     */
    private Specification<ProgrammeCase> viaProgramme(ProgrammeCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var p = subquery.from(Programme.class);
            subquery.select(p.get("id"))
                    .where(condition.build(root, query, cb, p));
            return root.get("programmeId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface ProgrammeCondition {
        Predicate build(Root<ProgrammeCase> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Programme> p);
    }
}
