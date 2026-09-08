package co.ara.onboarding.task;

import co.ara.onboarding.journey.TaskDirectory;
import co.ara.onboarding.journey.TaskSummary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * journey's own {@link TaskDirectory} port, implemented here rather than in
 * journey itself -- ModuleBoundaryTest.noJourneyDependencyOnTask is what a
 * concrete journey-side implementation naming a task type would violate. Same
 * shape as {@link TaskLifecycleAdapter}.
 *
 * Deliberately bypasses AuthorizedQuery, for the same reason
 * TaskLifecycleAdapter.instantiateForCase does: every milestone id this
 * receives has already been read by CaseService.roadmap() through its own
 * authorizedQuery.getById(Case...) and readCaseChild(Milestone...) calls under
 * CASE_VIEW, before summaryFor is ever called. These are not raw ids taken
 * from a URL or request body -- they are the caller's own already-authorized
 * result set. Re-checking authorization on ids the caller already resolved
 * would not be a new authorization boundary, only a second, redundant read of
 * the same case.
 *
 * Named as a named exclusion in
 * AuthorizationCoverageTest.FINDER_RULE_EXCLUSIONS for this reason, not by
 * avoiding a *Service/*Directory suffix -- sub-project 3A Task 2 rebound that
 * rule to bind on repository injection rather than class name, so this
 * exemption is now a reviewable line in that list rather than a naming
 * choice.
 *
 * One query, regardless of how many milestone ids are asked for -- the whole
 * reason {@link TaskDirectory#summaryFor} takes a Collection rather than one
 * id at a time (see that interface's own javadoc). A milestone with no tasks
 * at all never appears in TaskRepository.countsByMilestoneIds' result set, so
 * this method builds the full map starting from every REQUESTED id defaulted
 * to {@code TaskSummary(0, 0)}, then overlays whatever the query actually
 * returned -- a caller never sees a requested id absent from the map, or
 * mapped to null.
 */
@Component
public class TaskDirectoryAdapter implements TaskDirectory {

    private final TaskRepository tasks;

    public TaskDirectoryAdapter(TaskRepository tasks) {
        this.tasks = tasks;
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
        for (MilestoneTaskCounts row : tasks.countsByMilestoneIds(milestoneIds)) {
            result.put(row.milestoneId(), new TaskSummary(row.open().intValue(), row.total().intValue()));
        }
        return result;
    }
}
