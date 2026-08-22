package co.ara.onboarding.journey;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A requested approval against a {@link Case}: either leaving a stage
 * (STAGE_EXIT, targeting a stage) or forcing a milestone to completion
 * (FORCE_COMPLETE, targeting a milestone). The database CHECK
 * {@code approval_target_matches_kind} ties the target to the kind, so the two are
 * structurally impossible to conflate -- Task 17's two decide endpoints depend on it.
 * reason is NOT NULL: it is how Q5's mandatory-reason-in-the-audit-trail requirement
 * becomes unavoidable rather than a convention.
 */
@Entity
@Table(name = "approval")
public class Approval extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalKind kind;

    @Column(name = "stage_id")
    private UUID stageId;

    @Column(name = "milestone_id")
    private UUID milestoneId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ApprovalStatus status;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public ApprovalKind getKind() { return kind; }
    public void setKind(ApprovalKind kind) { this.kind = kind; }

    public UUID getStageId() { return stageId; }
    public void setStageId(UUID stageId) { this.stageId = stageId; }

    public UUID getMilestoneId() { return milestoneId; }
    public void setMilestoneId(UUID milestoneId) { this.milestoneId = milestoneId; }

    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public ApprovalStatus getStatus() { return status; }
    public void setStatus(ApprovalStatus status) { this.status = status; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
