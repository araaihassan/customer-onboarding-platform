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
 * Gate 2 of QA Q22: the customer's decision on a case's SCHEDULE (calendar dates,
 * named owners), issued as a dated, versioned snapshot. See {@link PlanRevisionItem}
 * for the frozen rows this revision captures.
 *
 * Unlike its items, the revision itself keeps its UPDATE grant -- {@code status}
 * genuinely transitions ISSUED -> APPROVED/REJECTED/SUPERSEDED (V21's own comment).
 * Only the captured items are frozen once written.
 *
 * There is no unique index on caseId alone: {@code plan_revision_case_number_uq}
 * scopes uniqueness to (case_id, revision_number) -- a case accumulates one row per
 * issued revision, same shape as {@code plan_shape_approval} -- and
 * {@code plan_revision_one_outstanding_uq} separately guarantees at most one row per
 * case sits ISSUED (outstanding) at a time; issuing a new one supersedes the old,
 * which is a later task's job to implement, not this one's.
 */
@Entity
@Table(name = "plan_revision")
public class PlanRevision extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "revision_number", nullable = false)
    private int revisionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanRevisionStatus status;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "issued_by", nullable = false)
    private UUID issuedBy;

    @Column(name = "issue_note")
    private String issueNote;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_on_behalf_of")
    private UUID decidedOnBehalfOf;

    @Column(name = "decision_note")
    private String decisionNote;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public int getRevisionNumber() { return revisionNumber; }
    public void setRevisionNumber(int revisionNumber) { this.revisionNumber = revisionNumber; }

    public PlanRevisionStatus getStatus() { return status; }
    public void setStatus(PlanRevisionStatus status) { this.status = status; }

    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }

    public UUID getIssuedBy() { return issuedBy; }
    public void setIssuedBy(UUID issuedBy) { this.issuedBy = issuedBy; }

    public String getIssueNote() { return issueNote; }
    public void setIssueNote(String issueNote) { this.issueNote = issueNote; }

    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }

    public UUID getDecidedBy() { return decidedBy; }
    public void setDecidedBy(UUID decidedBy) { this.decidedBy = decidedBy; }

    public UUID getDecidedOnBehalfOf() { return decidedOnBehalfOf; }
    public void setDecidedOnBehalfOf(UUID decidedOnBehalfOf) { this.decidedOnBehalfOf = decidedOnBehalfOf; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
}
