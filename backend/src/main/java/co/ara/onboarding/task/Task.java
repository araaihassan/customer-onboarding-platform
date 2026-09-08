package co.ara.onboarding.task;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A unit of work inside a running {@code Case} -- instantiated from a workflow
 * requirement of kind {@code TASK} ({@code requirementId} set) or created ad-hoc
 * ({@code requirementId} null; {@code task_requirement_uq} allows at most one task
 * per requirement either way). caseId is denormalised, as on {@code Milestone} and
 * {@code Requirement}, so "my work" -- a cross-case query by assignee -- and every
 * descriptor are one subquery hop to {@code onboarding_case} rather than a chain.
 * Completing a task never mutates progress directly: that still flows through
 * {@code RequirementService.satisfy} and {@code CaseEngine.reconcile}, under
 * {@code CaseRepository.lockById}'s row lock, same as every other runtime write.
 */
@Entity
@Table(name = "task")
public class Task extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "milestone_id", nullable = false)
    private UUID milestoneId;

    /** Null for an ad-hoc task; set when this task instantiates a requirement of kind TASK. */
    @Column(name = "requirement_id")
    private UUID requirementId;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskPriority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskStatus status;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** NOT NULL together with a CANCELLED status -- task_cancel_reason_ck refuses a silent waiver. */
    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** Deliberately NOT a foreign key -- the same soft-reference shape as Requirement.satisfiedRef. */
    @Column(name = "attachment_ref")
    private UUID attachmentRef;

    @Column(name = "attachment_ref_type")
    private String attachmentRefType;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }

    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID requirementId) { this.requirementId = requirementId; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public TaskPriority getPriority() { return priority; }
    public void setPriority(TaskPriority priority) { this.priority = priority; }

    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }

    public UUID getAssigneeId() { return assigneeId; }
    public void setAssigneeId(UUID assigneeId) { this.assigneeId = assigneeId; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public UUID getCompletedBy() { return completedBy; }
    public void setCompletedBy(UUID completedBy) { this.completedBy = completedBy; }

    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }

    public String getCancellationReason() { return cancellationReason; }
    public void setCancellationReason(String cancellationReason) { this.cancellationReason = cancellationReason; }

    public UUID getAttachmentRef() { return attachmentRef; }
    public void setAttachmentRef(UUID attachmentRef) { this.attachmentRef = attachmentRef; }

    public String getAttachmentRefType() { return attachmentRefType; }
    public void setAttachmentRefType(String attachmentRefType) { this.attachmentRefType = attachmentRefType; }
}
