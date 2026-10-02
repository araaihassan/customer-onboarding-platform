package co.ara.onboarding.agreement;

import co.ara.onboarding.workflow.AgreementRecordMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A SIGNATURE requirement's live instance -- one per requirement while its
 * status is not CANCELLED ({@code agreement_live_per_requirement_uq}); cancel
 * and create a fresh row to replace one (spec section 4.2, 5.8).
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass -- the
 * {@code document.DocumentRequest} shape: ordinary mutable entity with its own
 * {@code createdAt}/{@code updatedAt} columns set explicitly by the service
 * layer (Task 4+), not by a lifecycle callback, so a full-replace update can
 * touch {@code updatedAt} without touching {@code createdAt}.
 */
@Entity
@Table(name = "agreement")
public class Agreement {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "requirement_id", nullable = false)
    private UUID requirementId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_mode", nullable = false)
    private AgreementRecordMode recordMode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AgreementStatus status;

    @Column(name = "effective_date")
    private LocalDate effectiveDate;

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "renewal_date")
    private LocalDate renewalDate;

    @Column(name = "notice_period_days")
    private Integer noticePeriodDays;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "last_edited_by", nullable = false)
    private UUID lastEditedBy;

    @Column(name = "replaces_agreement_id")
    private UUID replacesAgreementId;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "signature_provider", nullable = false)
    private SignatureProviderKind signatureProvider;

    @Column(name = "provider_envelope_id")
    private String providerEnvelopeId;

    @Version
    @Column(name = "lock_version", nullable = false)
    private long lockVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID requirementId) { this.requirementId = requirementId; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public AgreementRecordMode getRecordMode() { return recordMode; }
    public void setRecordMode(AgreementRecordMode recordMode) { this.recordMode = recordMode; }

    public AgreementStatus getStatus() { return status; }
    public void setStatus(AgreementStatus status) { this.status = status; }

    public LocalDate getEffectiveDate() { return effectiveDate; }
    public void setEffectiveDate(LocalDate effectiveDate) { this.effectiveDate = effectiveDate; }

    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate expiresAt) { this.expiresAt = expiresAt; }

    public LocalDate getRenewalDate() { return renewalDate; }
    public void setRenewalDate(LocalDate renewalDate) { this.renewalDate = renewalDate; }

    public Integer getNoticePeriodDays() { return noticePeriodDays; }
    public void setNoticePeriodDays(Integer noticePeriodDays) { this.noticePeriodDays = noticePeriodDays; }

    public UUID getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(UUID ownerUserId) { this.ownerUserId = ownerUserId; }

    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID documentId) { this.documentId = documentId; }

    public UUID getLastEditedBy() { return lastEditedBy; }
    public void setLastEditedBy(UUID lastEditedBy) { this.lastEditedBy = lastEditedBy; }

    public UUID getReplacesAgreementId() { return replacesAgreementId; }
    public void setReplacesAgreementId(UUID replacesAgreementId) { this.replacesAgreementId = replacesAgreementId; }

    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }

    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }

    public SignatureProviderKind getSignatureProvider() { return signatureProvider; }
    public void setSignatureProvider(SignatureProviderKind signatureProvider) { this.signatureProvider = signatureProvider; }

    public String getProviderEnvelopeId() { return providerEnvelopeId; }
    public void setProviderEnvelopeId(String providerEnvelopeId) { this.providerEnvelopeId = providerEnvelopeId; }

    public long getLockVersion() { return lockVersion; }
    public void setLockVersion(long lockVersion) { this.lockVersion = lockVersion; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
