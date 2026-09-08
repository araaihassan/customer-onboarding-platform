package co.ara.onboarding.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A full replace of everything about a task except its lifecycle
 * (status/completion/cancellation -- Tasks 17-18's own gated transitions) and
 * its requirementId, which is set once at creation (by this task, or by Task
 * 19's instantiation path) and never reassigned by a general edit: a task
 * either instantiates a requirement or it doesn't, and that fact does not
 * change hands the way an owner or a due date does. Every field a client can
 * send here must round-trip through {@link TaskView} unchanged, or a caller
 * that reads-then-writes would silently blank whatever this record omits --
 * CLAUDE.md's PUT invariant.
 */
public record UpdateTaskRequest(@NotBlank String title, String description,
                                @NotNull TaskPriority priority, UUID assigneeId,
                                LocalDate dueDate, @NotNull UUID milestoneId) {}
