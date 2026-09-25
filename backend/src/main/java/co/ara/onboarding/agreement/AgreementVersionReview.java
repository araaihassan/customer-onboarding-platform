package co.ara.onboarding.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A reviewer's decision on one submitted {@link AgreementVersion} -- append-only
 * evidence, the same shape as {@link AgreementVersion}: one all-args constructor,
 * no setters, {@code GRANT SELECT, INSERT} only (V25). One row per version
 * ({@code agreement_version_id} is itself UNIQUE) -- a resubmission after REJECT
 * is a new {@link AgreementVersion}, not a second review of the old one.
 */
@Entity
@Table(name = "agreement_version_review")
public class AgreementVersionReview {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agreement_version_id", nullable = false)
    private UUID agreementVersionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewDecision decision;

    @Column(name = "reviewer_id", nullable = false)
    private UUID reviewerId;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    private String reason;

    protected AgreementVersionReview() {} // required by JPA

    public AgreementVersionReview(UUID id, UUID tenantId, UUID agreementVersionId,
                                   ReviewDecision decision, UUID reviewerId, Instant reviewedAt,
                                   String reason) {
        this.id = id;
        this.tenantId = tenantId;
        this.agreementVersionId = agreementVersionId;
        this.decision = decision;
        this.reviewerId = reviewerId;
        this.reviewedAt = reviewedAt;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgreementVersionId() { return agreementVersionId; }
    public ReviewDecision getDecision() { return decision; }
    public UUID getReviewerId() { return reviewerId; }
    public Instant getReviewedAt() { return reviewedAt; }
    public String getReason() { return reason; }
}
