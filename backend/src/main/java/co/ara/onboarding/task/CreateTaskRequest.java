package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * milestoneId is mandatory -- a task always belongs to a milestone, even an
 * ad-hoc one (task_requirement_uq only constrains requirementId, not
 * milestoneId; V16__task.sql's milestone_id column is itself NOT NULL).
 * requirementId is null for the ad-hoc case this task creates; a non-null
 * value here is Task 19's own instantiation path re-using this same
 * constructor shape, not something this record forbids -- but Task 16 never
 * trusts it blindly: {@link TaskService#create} resolves it through
 * AuthorizedQuery before writing, the same as every other id below.
 */
public record CreateTaskRequest(@NotNull UUID milestoneId, UUID requirementId,
                                @NotBlank String title, String description,
                                @NotNull TaskPriority priority, UUID assigneeId,
                                LocalDate dueDate) {}
