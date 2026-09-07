package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * Reopening a milestone must reopen the tasks completed inside it, or the
 * milestone shows requirements satisfied by tasks still marked complete, with
 * no way to clear them. Called from MilestoneService.reopen, same transaction.
 *
 * Cancelled tasks are NOT reopened -- reopening a milestone must not resurrect
 * work somebody deliberately abandoned.
 */
public interface TaskLifecycle {
    void instantiateForCase(UUID caseId);
    void reopenForMilestone(UUID milestoneId);
}
