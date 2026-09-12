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
 * An explicit grant of a document to a principal (contact, user or department)
 * outside the tier/targeting audience predicate's default reach. Revocation is a
 * column, not a DELETE: who could once see a document is part of the record, and
 * DELETE is denied at the database anyway ({@code document_share_live_uq}'s
 * partial index is what lets the same principal be re-granted after a revoke).
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass -- same reasoning as
 * {@link DocumentVersion}: the table has no {@code updated_at} column, only
 * {@code grantedAt} (immutable) and {@code revokedAt} (the one mutable field).
 */
@Entity
@Table(name = "document_share")
public class DocumentShare {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false)
    private SharePrincipalType principalType;

    /** Soft reference, typed by principalType -- a contact, app_user or department id. */
    @Column(name = "principal_id", nullable = false)
    private UUID principalId;

    @Column(name = "granted_by", nullable = false)
    private UUID grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected DocumentShare() {} // required by JPA

    public DocumentShare(UUID id, UUID tenantId, UUID documentId, SharePrincipalType principalType,
                          UUID principalId, UUID grantedBy, Instant grantedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.principalType = principalType;
        this.principalId = principalId;
        this.grantedBy = grantedBy;
        this.grantedAt = grantedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDocumentId() { return documentId; }
    public SharePrincipalType getPrincipalType() { return principalType; }
    public UUID getPrincipalId() { return principalId; }
    public UUID getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
}
