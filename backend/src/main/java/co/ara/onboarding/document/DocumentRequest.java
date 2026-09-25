package co.ara.onboarding.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A request for a customer contact to supply a document -- ad-hoc
 * ({@code requirementId} null) or requirement-instantiated (non-null), exactly
 * {@code task}'s shape ({@code document_request_requirement_uq} mirrors
 * {@code task_requirement_uq}: at most one request per requirement). Fulfilling
 * one sets {@code status} to FULFILLED and {@code fulfilledDocumentId} together --
 * {@code document_request_fulfilled_ck} refuses the first without the second.
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass -- same reasoning as
 * {@link DocumentVersion}: the table has no {@code updated_at} column, only
 * {@code requestedAt} (immutable). Unlike {@code DocumentVersion}/{@code DocumentShare}/
 * {@code DocumentCaseLink}, this entity is otherwise fully mutable -- a request has
 * a real multi-field lifecycle (status, and the fields fulfilment sets), not one
 * append-then-flag-flip -- so it exposes ordinary setters rather than an
 * immutable-construction shape.
 */
@Entity
@Table(name = "document_request")
public class DocumentRequest {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    /** Null for an ad-hoc request; set when this request instantiates a requirement of kind DOCUMENT. */
    @Column(name = "requirement_id")
    private UUID requirementId;

    @Column(name = "requested_of_contact_id")
    private UUID requestedOfContactId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentCategory category;

    private String description;

    @Column(name = "due_at")
    private Instant dueAt;

    @Column(name = "requires_review", nullable = false)
    private boolean requiresReview;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentRequestStatus status;

    @Column(name = "fulfilled_document_id")
    private UUID fulfilledDocumentId;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getCaseId() { return caseId; }
    public void setCaseId(UUID caseId) { this.caseId = caseId; }

    public UUID getRequirementId() { return requirementId; }
    public void setRequirementId(UUID requirementId) { this.requirementId = requirementId; }

    public UUID getRequestedOfContactId() { return requestedOfContactId; }
    public void setRequestedOfContactId(UUID requestedOfContactId) { this.requestedOfContactId = requestedOfContactId; }

    public DocumentCategory getCategory() { return category; }
    public void setCategory(DocumentCategory category) { this.category = category; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public boolean isRequiresReview() { return requiresReview; }
    public void setRequiresReview(boolean requiresReview) { this.requiresReview = requiresReview; }

    public DocumentRequestStatus getStatus() { return status; }
    public void setStatus(DocumentRequestStatus status) { this.status = status; }

    public UUID getFulfilledDocumentId() { return fulfilledDocumentId; }
    public void setFulfilledDocumentId(UUID fulfilledDocumentId) { this.fulfilledDocumentId = fulfilledDocumentId; }

    public UUID getRequestedBy() { return requestedBy; }
    public void setRequestedBy(UUID requestedBy) { this.requestedBy = requestedBy; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant requestedAt) { this.requestedAt = requestedAt; }
}
