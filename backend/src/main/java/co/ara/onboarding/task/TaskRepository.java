package co.ara.onboarding.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TaskRepository
        extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {

    /** Every task on a case, for the Tasks tab. */
    List<Task> findByCaseId(UUID caseId);

    /** "My work": a cross-case query, which is why case_id is denormalised onto task. */
    List<Task> findByAssigneeId(UUID assigneeId);

    /** Every task on a milestone, for TaskLifecycle.reopenForMilestone (Task 19/20). */
    List<Task> findByMilestoneId(UUID milestoneId);

    /**
     * Grouped counts for TaskDirectoryAdapter.summaryFor -- one query regardless
     * of how many milestone ids are passed, which is the entire reason the
     * journey.TaskDirectory port takes a Collection instead of one id at a time.
     * A milestone id with no matching rows is simply absent from the result --
     * TaskDirectoryAdapter, not this query, is responsible for filling it back
     * in as (0, 0).
     */
    @Query("""
            select new co.ara.onboarding.task.MilestoneTaskCounts(
                t.milestoneId,
                sum(case when t.status = co.ara.onboarding.task.TaskStatus.COMPLETED
                          or t.status = co.ara.onboarding.task.TaskStatus.CANCELLED
                     then 0L else 1L end),
                count(t))
            from Task t
            where t.milestoneId in :milestoneIds
            group by t.milestoneId
            """)
    List<MilestoneTaskCounts> countsByMilestoneIds(@Param("milestoneIds") Collection<UUID> milestoneIds);
}
