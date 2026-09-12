package co.ara.onboarding.document;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A document scoped to a journey (PRD section 10), never to a customer account
 * directly -- {@link DocumentCaseLink} is the explicit cross-journey share that
 * rule requires. {@code customerId} is denormalised from the case (safe: a case
 * never changes customer) so the portal audience predicate avoids a join on every
 * read. Visibility is two axes: {@code visibilityTier} (how broadly) and
 * {@code targetDepartmentId}/{@code targetContactLabel} (which group) -- QA Q9 and
 * its 2026-08-29 amendment. {@code currentVersionId} is deliberately NOT a foreign
 * key -- {@link DocumentVersion} references {@code document}, and a document
 * pointing back would be a cycle; the application keeps it in step on every
 * version append.
 */
@Entity
@Table(name = "document")
public class Document extends TenantScopedEntity {

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_tier", nullable = false)
    private VisibilityTier visibilityTier;

    @Column(name = "target_department_id")
    private UUID targetDepartmentId;

    @Column(name = "target_contact_label")
    private String targetContactLabel;

    /** Set only when visibilityTier is CONTACT_ONLY -- document_owner_ck enforces this at the database. */
    @Column(name = "owner_contact_id")
    private UUID ownerContactId;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentStatus status;

    /** Kept in step with the latest DocumentVersion row on every append. */
    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getCustomerId() { return customerId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public DocumentCategory getCategory() { return category; }
    public void setCategory(DocumentCategory category) { this.category = category; }

    public VisibilityTier getVisibilityTier() { return visibilityTier; }
    public void setVisibilityTier(VisibilityTier visibilityTier) { this.visibilityTier = visibilityTier; }

    public UUID getTargetDepartmentId() { return targetDepartmentId; }
    public void setTargetDepartmentId(UUID targetDepartmentId) { this.targetDepartmentId = targetDepartmentId; }

    public String getTargetContactLabel() { return targetContactLabel; }
    public void setTargetContactLabel(String targetContactLabel) { this.targetContactLabel = targetContactLabel; }

    public UUID getOwnerContactId() { return ownerContactId; }
    public void setOwnerContactId(UUID ownerContactId) { this.ownerContactId = ownerContactId; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public DocumentStatus getStatus() { return status; }
    public void setStatus(DocumentStatus status) { this.status = status; }

    public UUID getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(UUID currentVersionId) { this.currentVersionId = currentVersionId; }

    public UUID getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }
}
