package co.ara.onboarding.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The explicit cross-journey share a document scoped to one case (PRD section 10)
 * needs in order to also be visible from another. Unlinking is a column, not a
 * DELETE, the same reasoning as {@link DocumentShare}'s {@code revokedAt} --
 * {@code document_case_link_live_uq}'s partial index is what lets the same
 * document be re-linked to a case after an unlink.
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass -- same reasoning as
 * {@link DocumentVersion}: the table has no {@code updated_at} column, only
 * {@code linkedAt} (immutable) and {@code revokedAt} (the one mutable field).
 */
@Entity
@Table(name = "document_case_link")
public class DocumentCaseLink {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "linked_by", nullable = false)
    private UUID linkedBy;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DocumentCaseLink() {} // required by JPA

    public DocumentCaseLink(UUID id, UUID tenantId, UUID documentId, UUID caseId,
                             UUID linkedBy, Instant linkedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.caseId = caseId;
        this.linkedBy = linkedBy;
        this.linkedAt = linkedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDocumentId() { return documentId; }
    public UUID getCaseId() { return caseId; }
    public UUID getLinkedBy() { return linkedBy; }
    public Instant getLinkedAt() { return linkedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
