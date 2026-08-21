package co.ara.onboarding.journey;

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
 * The runtime instance of a {@code milestone_definition} within one {@link Case}.
 * caseId is denormalised here (rather than reached only through the definition) so
 * every descriptor is one subquery hop to onboarding_case instead of a chain.
 */
@Entity
@Table(name = "milestone")
public class Milestone extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "milestone_definition_id", nullable = false)
    private UUID milestoneDefinitionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MilestoneStatus status;

    @Column(name = "owner_user_id")
    private UUID ownerUserId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "progress_percent", nullable = false)
    private int progressPercent;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "completed_by")
    private UUID completedBy;

    /** Only set when the completion was forced -- see {@code approval}. */
    @Column(name = "completion_reason")
    private String completionReason;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getMilestoneDefinitionId() { return milestoneDefinitionId; }
    public void setMilestoneDefinitionId(UUID milestoneDefinitionId) { this.milestoneDefinitionId = milestoneDefinitionId; }

    public MilestoneStatus getStatus() { return status; }
    public void setStatus(MilestoneStatus status) { this.status = status; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public UUID getCompletedBy() { return completedBy; }
    public void setCompletedBy(UUID completedBy) { this.completedBy = completedBy; }

    public String getCompletionReason() { return completionReason; }
    public void setCompletionReason(String completionReason) { this.completionReason = completionReason; }
}
