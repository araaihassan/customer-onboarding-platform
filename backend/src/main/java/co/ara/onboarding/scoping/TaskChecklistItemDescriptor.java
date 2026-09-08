package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthContext;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.ResourceAuthorizationDescriptor;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.task.TaskChecklistItem;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * A checklist item carries no scoping data of its own -- {@code task_id} is its
 * only foreign reference (see {@code TaskChecklistItem}'s own javadoc). No
 * permission is catalogued against a "task_checklist_item" resource type, and
 * {@code DescriptorRegistry.validate()} never asks for one -- but
 * {@code AuthorizationPredicateBuilder.forPermission} dispatches a descriptor by
 * ENTITY TYPE, independent of the permission's own catalogued resourceType, so
 * {@code ChecklistService} reading a {@code TaskChecklistItem} under
 * task.manage/task.complete would otherwise hit
 * {@code DescriptorRegistry.forEntity(TaskChecklistItem.class)} with nothing
 * registered. This class exists purely to supply that entry, the exact same
 * reason {@code CaseParticipantDescriptor} and {@code CaseAttributeValueDescriptor}
 * exist (CLAUDE.md: "a future entity read the same way needs the same second
 * descriptor, and validate() alone will not remind anyone to add it" -- this is
 * that future entity).
 *
 * Every predicate is one hop further than {@link TaskDescriptor}'s own: item ->
 * task -> case. ASSIGNED resolves through the parent task's own assignee_id
 * column, identically to TaskDescriptor's ASSIGNED -- a checklist item has no
 * assignee of its own; it borrows its parent task's personal relationship
 * rather than inventing a second one.
 */
@Component
public class TaskChecklistItemDescriptor implements ResourceAuthorizationDescriptor<TaskChecklistItem> {

    @Override public String resourceType() { return "task_checklist_item"; }

    @Override public Class<TaskChecklistItem> entityType() { return TaskChecklistItem.class; }

    /** Unused -- assignedScope reads the parent task's assignee_id column directly. Same note as TaskDescriptor. */
    @Override public Set<RelationshipType> assignedRelationships() {
        return Set.of(RelationshipType.OWNER, RelationshipType.ASSIGNEE,
                      RelationshipType.PARTICIPANT, RelationshipType.APPROVER);
    }

    @Override public Specification<TaskChecklistItem> departmentScope(AuthContext ctx) {
        return viaTask((root, query, cb, t) -> {
            if (ctx.departmentId() == null) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(cb.equal(c.get("owningDepartmentId"), ctx.departmentId()));
            return t.get("caseId").in(caseSub);
        });
    }

    @Override public Specification<TaskChecklistItem> teamScope(AuthContext ctx) {
        return viaTask((root, query, cb, t) -> {
            if (ctx.teamIds().isEmpty()) return cb.disjunction();
            var caseSub = query.subquery(UUID.class);
            var c = caseSub.from(Case.class);
            caseSub.select(c.get("id")).where(c.get("owningTeamId").in(ctx.teamIds()));
            return t.get("caseId").in(caseSub);
        });
    }

    /**
     * Personal and not team-mediated, same invariant TaskDescriptor's own
     * assignedScope states: a checklist item on a task assigned to a fellow
     * team member, but not to this actor, must not match.
     */
    @Override public Specification<TaskChecklistItem> assignedScope(AuthContext ctx) {
        return viaTask((root, query, cb, t) -> cb.equal(t.get("assigneeId"), ctx.userId()));
    }

    /**
     * The subquery selects task ids matching the condition and tests taskId
     * against them. RLS still applies to both the task and (nested) case
     * subqueries' own tables, so an item cannot be reached through a task or
     * case in another tenant.
     */
    private Specification<TaskChecklistItem> viaTask(TaskCondition condition) {
        return (root, query, cb) -> {
            var subquery = query.subquery(UUID.class);
            var t = subquery.from(Task.class);
            subquery.select(t.get("id")).where(condition.build(root, query, cb, t));
            return root.get("taskId").in(subquery);
        };
    }

    @FunctionalInterface
    private interface TaskCondition {
        Predicate build(Root<TaskChecklistItem> root, CriteriaQuery<?> query,
                        CriteriaBuilder cb, Root<Task> t);
    }
}
