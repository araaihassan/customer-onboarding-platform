package co.ara.onboarding.task;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.TaskDirectory;
import co.ara.onboarding.journey.TaskSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;

/**
 * journey's own {@link TaskDirectory} port, implemented here rather than in
 * journey itself -- ModuleBoundaryTest.noJourneyDependencyOnTask is what a
 * concrete journey-side implementation naming a task type would violate. Same
 * shape as {@link TaskLifecycleAdapter}.
 *
 * Routes the count through {@link AuthorizedQuery} under
 * {@link PermissionKeys#TASK_VIEW} -- sub-project 3A Task 5's fix.
 * The earlier version bypassed AuthorizedQuery on the reasoning that every
 * milestone id here was already read by CaseService.roadmap() through its own
 * CASE_VIEW-gated resolution, so re-checking would be a second, redundant
 * read of the same case. That reasoning conflated CASE_VIEW with TASK_VIEW:
 * an actor who may see a case at all is not necessarily an actor who may see
 * every task on it -- an ASSIGNED-scoped task.view holder (Sales
 * Representative, Service Provider, Business Partner in the seeded
 * templates) sees a task's own assignee_id, not anything about the parent
 * case -- so the milestone id being pre-authorized under CASE_VIEW said
 * nothing about which TASKS on it this reader may count. The unscoped count
 * leaked tasks assigned to somebody else into a number the reader could not
 * open, exactly the aggregate-only leak shape spec §6.4 refuses to repeat
 * for the programme rollup.
 *
 * No longer a named exclusion in AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS:
 * the only repository call this class now makes is authorizedQuery.findAll,
 * which the rule's own owner-based carve-out already permits (the "reach
 * finders THROUGH AuthorizedQuery, never around it" line), so nothing here
 * needs a class-level exemption any more.
 *
 * Still one query, regardless of how many milestone ids are asked for -- the
 * whole reason {@link TaskDirectory#summaryFor} takes a Collection rather
 * than one id at a time (see that interface's own javadoc): the IN clause is
 * still a single SQL statement, only now ANDed with the scope predicate
 * AuthorizedQuery applies, and grouping happens in memory afterwards rather
 * than in the query itself. A milestone with no VISIBLE tasks at all never
 * appears in the grouped map, so this method builds the full map starting
 * from every REQUESTED id defaulted to {@code TaskSummary(0, 0)}, then
 * overlays whatever the scoped read actually returned -- a caller never sees
 * a requested id absent from the map, or mapped to null.
 */
@Component
public class TaskDirectoryAdapter implements TaskDirectory {

    private final TaskRepository tasks;
    private final AuthorizedQuery authorizedQuery;

    public TaskDirectoryAdapter(TaskRepository tasks, AuthorizedQuery authorizedQuery) {
        this.tasks = tasks;
        this.authorizedQuery = authorizedQuery;
    }

    @Override
    public Map<UUID, TaskSummary> summaryFor(Collection<UUID> milestoneIds) {
        Map<UUID, TaskSummary> result = new HashMap<>();
        for (UUID id : milestoneIds) {
            result.put(id, new TaskSummary(0, 0));
        }
        if (milestoneIds.isEmpty()) {
            return result;
        }

        Specification<Task> byMilestones = (root, query, cb) -> root.get("milestoneId").in(milestoneIds);
        List<Task> visible = authorizedQuery.findAll(
                        tasks, Task.class, PermissionKeys.TASK_VIEW, byMilestones, Pageable.unpaged())
                .getContent();

        for (var entry : visible.stream().collect(groupingBy(Task::getMilestoneId)).entrySet()) {
            List<Task> onMilestone = entry.getValue();
            long open = onMilestone.stream()
                    .filter(t -> t.getStatus() != TaskStatus.COMPLETED && t.getStatus() != TaskStatus.CANCELLED)
                    .count();
            result.put(entry.getKey(), new TaskSummary((int) open, onMilestone.size()));
        }
        return result;
    }
}
