package co.ara.onboarding.task;

import co.ara.onboarding.journey.TaskLifecycle;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * journey's own {@link TaskLifecycle} port, implemented here rather than in
 * journey itself -- ModuleBoundaryTest.noJourneyDependencyOnTask is what a
 * concrete journey-side implementation naming a task type would violate.
 *
 * Both methods are implemented in this one task even though only
 * instantiateForCase has a caller yet (CaseService.create, Task 19):
 * reopenForMilestone has no caller until Task 20 wires MilestoneService.reopen
 * to call it, but Spring cannot construct a bean satisfying only half an
 * interface -- a bean implementing one method would not compile, so nothing
 * using TaskLifecycle would start.
 */
@Component
public class TaskLifecycleAdapter implements TaskLifecycle {

    private final TaskInstantiation instantiation;
    private final TaskRepository tasks;

    public TaskLifecycleAdapter(TaskInstantiation instantiation, TaskRepository tasks) {
        this.instantiation = instantiation;
        this.tasks = tasks;
    }

    /**
     * Called from CaseService.create, after CASE_CREATED is audited and
     * before engine.reconcile runs, inside that method's own transaction, on
     * a caseId it just created and fully controls -- see TaskInstantiation's
     * own javadoc for why this deliberately bypasses AuthorizedQuery rather
     * than by oversight.
     */
    @Override
    public void instantiateForCase(UUID caseId) {
        instantiation.instantiateForCase(caseId);
    }

    /**
     * Spec §5.3: COMPLETED tasks on this milestone return to PENDING, with
     * completedAt/completedBy cleared. CANCELLED tasks are left exactly as
     * they are -- reopening a milestone must not resurrect work somebody
     * deliberately abandoned. No caller yet; Task 20 wires
     * MilestoneService.reopen to call this, in that method's own transaction.
     */
    @Override
    public void reopenForMilestone(UUID milestoneId) {
        for (Task t : tasks.findByMilestoneId(milestoneId)) {
            if (t.getStatus() == TaskStatus.COMPLETED) {
                t.setStatus(TaskStatus.PENDING);
                t.setCompletedAt(null);
                t.setCompletedBy(null);
                tasks.save(t);
            }
        }
    }
}
