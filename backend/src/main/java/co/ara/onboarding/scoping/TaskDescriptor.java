package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.task.Task;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * Tasks inherit DEPARTMENT and TEAM from the case they belong to, the same
 * viaCase shape as MilestoneDescriptor and CaseAttributeValueDescriptor. ASSIGNED
 * is deliberately different from every other case-scoped sibling: it resolves
 * through the task's own assignee_id column alone, never through
 * case_participant (design spec 6.2, plan Task 13). ASSIGNED is a personal
 * relationship -- the RelationshipType invariant CLAUDE.md states -- and a task
 * can be assigned to someone with no case_participant row of their own at all,
 * a portal contact included (spec 6.4).
 */
@Component
public class TaskDescriptor implements ResourceAuthorizationDescriptor<Task> {

    @Override public String resourceType() { return "task"; }

    @Override public Class<Task> entityType() { return Task.class; }

    /**
     * Unused here -- assignedScope reads the task's own assignee_id column
     * instead of case_participant relationships -- but the interface requires an
     * answer. Returning the same set every case-scoped sibling does keeps this
     * consistent rather than inventing a different one for no reason
     * (CaseParticipantDescriptor carries the identical note).
     */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<Task> departmentScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.departmentId() == null
                ? cb.disjunction()
                : cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
    }

    @Override public Specification<Task> teamScope(AuthContext ctx) {
        return viaCase((root, query, cb, c) -> ctx.teamIds().isEmpty()
                ? cb.disjunction()
                : c.get("owningTeamId").in(ctx.teamIds()));
    }

    /**
     * Personal and not team-mediated: a task assigned to a member of the owning
     * team, but not to this actor, must not match. Comparing assignee_id directly
     * also fails closed for an unassigned task -- SQL's assignee_id IS NULL never
     * equals a non-null actor id.
     */
    @Override public Specification<Task> assignedScope(AuthContext ctx) {
        return (root, query, cb) -> cb.equal(root.get("assigneeId"), ctx.userId());
    }

    /**
     * The subquery selects case ids matching the condition and tests caseId
     * against them. RLS still applies to the subquery's own table, so a task
     * cannot be reached through a case in another tenant.
     */
    private Specification<Task> viaCase(CaseCondition condition) {
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
        Predicate build(Root<Task> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Case> c);
    }
}
