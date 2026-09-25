package co.ara.onboarding.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One uploaded copy of a {@link Document}'s content -- immutable in its CONTENT,
 * mutable in its REVIEW OUTCOME (V23's {@code document_version_immutable_trg}
 * enforces this at the database; only {@code reviewStatus}/{@code reviewedBy}/
 * {@code reviewedAt}/{@code reviewNote} carry setters here, for the same reason
 * {@code journey.PlanRevisionItem}'s javadoc gives a field that can never legally
 * change no business exposing a method that changes it).
 *
 * Deliberately NOT a {@code TenantScopedEntity} subclass, the same reasoning
 * {@code journey.PlanRevisionItem} and {@code authz.UserRole} both give: that
 * superclass chain supplies {@code updated_at}, and {@code document_version} has
 * none -- {@code uploadedAt} is its only timestamp, and it never changes once set.
 * Tenant isolation for this table rests entirely on the database
 * ({@code enable_tenant_rls('document_version')}), enforced once rather than twice.
 */
@Entity
@Table(name = "document_version")
public class DocumentVersion {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "storage_key", nullable = false)
    private String storageKey;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    /**
     * Hex SHA-256 of the uploaded bytes, computed alongside the same stream at
     * upload time. The column is {@code char(64)} (bpchar); a plain String field
     * defaults to Types#VARCHAR regardless of columnDefinition, which
     * SchemaValidation then refuses to accept as a match against the real bpchar
     * column -- @JdbcTypeCode(SqlTypes.CHAR) is what actually changes Hibernate's
     * own expected JDBC type, not columnDefinition (DDL-generation only).
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, columnDefinition = "char(64)")
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false)
    private ReviewStatus reviewStatus;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "review_note")
    private String reviewNote;

    @Column(name = "uploaded_by", nullable = false)
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    protected DocumentVersion() {} // required by JPA

    public DocumentVersion(UUID id, UUID tenantId, UUID documentId, int versionNo,
                            String storageKey, long sizeBytes, String contentType, String sha256,
                            ReviewStatus reviewStatus, UUID uploadedBy, Instant uploadedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.versionNo = versionNo;
        this.storageKey = storageKey;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.sha256 = sha256;
        this.reviewStatus = reviewStatus;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = uploadedAt;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getDocumentId() { return documentId; }
    public int getVersionNo() { return versionNo; }
    public String getStorageKey() { return storageKey; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public String getSha256() { return sha256; }
    public UUID getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }

    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(ReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }

    public UUID getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(UUID reviewedBy) { this.reviewedBy = reviewedBy; }

    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
}
