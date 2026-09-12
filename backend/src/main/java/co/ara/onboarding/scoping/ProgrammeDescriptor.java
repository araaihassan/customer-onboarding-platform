package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.programme.Programme;
import co.ara.onboarding.programme.ProgrammeParticipant;
import co.ara.onboarding.programme.ProgrammeParticipantStatus;
import co.ara.onboarding.programme.ProgrammeStatus;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
public class ProgrammeDescriptor implements ResourceAuthorizationDescriptor<Programme> {

    @Override public String resourceType() { return "programme"; }
    @Override public Class<Programme> entityType() { return Programme.class; }

    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.PARTICIPANT);
    }

    @Override public Specification<Programme> departmentScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(root.get("owningDepartmentId"), ctx.departmentId());
    }

    @Override public Specification<Programme> teamScope(AuthContext ctx) {
        return (root, query, cb) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : root.get("owningTeamId").in(ctx.teamIds());
    }

    /**
     * ASSIGNED resolves through programme_participant on the actor -- and that is the
     * ONLY thing programme participation grants. It confers no access to any journey
     * the programme contains; those come from case_participant rows written explicitly
     * (spec 6.3). A predicate here that reached into onboarding_case would be exactly
     * the scope-widening backdoor three sub-project 1 escalations took.
     *
     * Also requires the programme itself to be ACTIVE -- a correction made alongside
     * Task 12, not carried over from Task 11 unexamined. Task 12's brief predicted
     * "deactivate sets status = INACTIVE and does nothing else -- the descriptor's
     * assignedScope already excludes inactive programmes" as though this clause were
     * already here; it was not; this method previously read only programme_participant
     * and never Programme.status at all, so deactivating a programme left every
     * participant's ASSIGNED read completely unaffected -- programmeService.deactivate
     * would have set a column nobody's access actually depended on.
     * ProgrammeServiceTest.deactivationRevokesTheCrossJourneyReadStructurally proves it
     * red without this clause. departmentScope/teamScope deliberately do NOT get the
     * same treatment: a DEPARTMENT- or TEAM-scoped programme.view/programme.manage
     * holder (a department lead, an administrator) can still see a deactivated
     * programme for management and reporting purposes -- only the narrower,
     * participation-mediated grant is structurally cut off by deactivation, which is
     * consistent with "deactivate the record, do not erase who could see it for
     * governance reasons" and with ALL scope (short-circuited to conjunction() in
     * AuthorizationPredicateBuilder, before any descriptor runs) always seeing it
     * regardless.
     */
    @Override public Specification<Programme> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> {
            if (ctx.userId() == null) return cb.disjunction();
            Subquery<UUID> sub = query.subquery(UUID.class);
            Root<ProgrammeParticipant> p = sub.from(ProgrammeParticipant.class);
            sub.select(p.get("programmeId")).where(
                    cb.equal(p.get("userId"), ctx.userId()),
                    cb.equal(p.get("status"), ProgrammeParticipantStatus.ACTIVE));
            return cb.and(root.get("id").in(sub), cb.equal(root.get("status"), ProgrammeStatus.ACTIVE));
        };
    }
}
