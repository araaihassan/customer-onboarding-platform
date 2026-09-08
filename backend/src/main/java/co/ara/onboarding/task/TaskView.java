package co.ara.onboarding.task;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Must carry every field {@link UpdateTaskRequest} accepts -- title,
 * description, priority, assigneeId, dueDate, milestoneId -- so a client that
 * reads this view and PUTs it back verbatim never silently blanks a field
 * (CLAUDE.md's full-replace invariant). requirementId and status are here too,
 * ahead of Tasks 17-18's transitions and Task 19's instantiation, because
 * neither is ever supplied by a request -- they are read-only facts about the
 * task, exactly like CaseMilestoneView's own completedAt/completedBy/
 * completionReason. completedAt/completedBy/cancelledAt/cancellationReason are
 * included for the same reason, ahead of need, so Tasks 17-18 (whose own file
 * lists modify TaskService only, never this file) have somewhere to put a
 * transition's result. attachmentRef/attachmentRefType are deliberately
 * omitted -- that seam belongs to sub-project 4 (Documents), out of scope here.
 */
public record TaskView(UUID id, UUID caseId, UUID milestoneId, UUID requirementId,
                       String title, String description, TaskPriority priority,
                       TaskStatus status, UUID assigneeId, LocalDate dueDate,
                       Instant completedAt, UUID completedBy,
                       Instant cancelledAt, String cancellationReason) {}
