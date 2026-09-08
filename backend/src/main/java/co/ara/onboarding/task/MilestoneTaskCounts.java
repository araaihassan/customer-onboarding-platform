package co.ara.onboarding.task;

import java.util.UUID;

/**
 * One grouped row from {@link TaskRepository#countsByMilestoneIds}: how many
 * of a milestone's tasks are still open, and how many exist in total. Package-
 * private -- journey never sees this shape, only the {@code
 * co.ara.onboarding.journey.TaskSummary} {@link TaskDirectoryAdapter} maps it to.
 */
record MilestoneTaskCounts(UUID milestoneId, Long open, Long total) {}
