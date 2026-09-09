package co.ara.onboarding.workflow;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The customer's decision on a plan's SHAPE (stages, milestones, requirements,
 * estimated durations) -- gate 1 of QA Q22, once per {@link WorkflowVersion}.
 *
 * A separate table rather than columns on workflow_version, because
 * workflow_version_frozen refuses every UPDATE to a non-DRAFT row: approval columns
 * on the version itself would be unwritable the moment an approval needs recording
 * -- exactly when the version has just been published. There is no snapshot here
 * and none is needed: publish already froze the graph, so "what shape did they
 * approve?" is answered by reading the version itself.
 *
 * There is deliberately no unique index on versionId -- a version can be submitted,
 * rejected and resubmitted, and each submission is a NEW row. "Current state" means
 * "the latest row for this version", read by submittedAt DESC ordering via
 * plan_shape_approval_version_idx.
 */
@Entity
@Table(name = "plan_shape_approval")
public class PlanShapeApproval extends TenantScopedEntity {

    @Column(name = "version_id", nullable = false)
    private UUID versionId;

    @Column(name = "template_id", nullable = false)
    private UUID templateId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanShapeApprovalStatus status;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_on_behalf_of")
    private UUID decidedOnBehalfOf;

    @Column(name = "decision_note")
    private String decisionNote;

    public UUID getVersionId() { return versionId; }
    public void setVersionId(UUID versionId) { this.versionId = versionId; }

    public UUID getTemplateId() { return templateId; }
    public void setTemplateId(UUID templateId) { this.templateId = templateId; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public PlanShapeApprovalStatus getStatus() { return status; }
    public void setStatus(PlanShapeApprovalStatus status) { this.status = status; }

    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }

    public UUID getSubmittedBy() { return submittedBy; }
    public void setSubmittedBy(UUID submittedBy) { this.submittedBy = submittedBy; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public UUID getDecidedOnBehalfOf() { return decidedOnBehalfOf; }
    public void setDecidedOnBehalfOf(UUID decidedOnBehalfOf) { this.decidedOnBehalfOf = decidedOnBehalfOf; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
