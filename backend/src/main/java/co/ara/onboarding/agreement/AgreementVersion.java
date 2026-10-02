package co.ara.onboarding.agreement;

import co.ara.onboarding.workflow.AgreementRecordMode;
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
 * One submitted draft of an {@link Agreement} -- append-only evidence, the
 * {@code document.DocumentVersion}/{@code journey.PlanRevisionItem} shape:
 * one all-args constructor, no setters, {@code GRANT SELECT, INSERT} only (V25)
 * with no UPDATE/DELETE granted at all -- unlike {@code DocumentVersion}, this
 * table has no mutable "review outcome" columns; review decisions live in their
 * own append-only {@link AgreementVersionReview} row instead.
 *
 * {@code recordMode} is copied from the owning {@link Agreement} at submission
 * time so {@code agreement_version_file_ck} needs no join and this row never
 * needs an UPDATE if the agreement's own mode could ever change (it cannot,
 * post-creation, but the copy is what lets the CHECK be expressed here at all).
 *
 * {@code structuredSnapshot} maps its jsonb column with
 * {@code @JdbcTypeCode(SqlTypes.JSON)}, exactly {@code audit.AuditEvent.payload}'s
 * existing precedent (not a new pattern in this codebase -- confirmed by reading
 * that field before writing this one).
 */
@Entity
@Table(name = "agreement_version")
public class AgreementVersion {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_mode", nullable = false)
    private AgreementRecordMode recordMode;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "last_edited_by", nullable = false)
    private UUID lastEditedBy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "structured_snapshot", nullable = false, columnDefinition = "jsonb")
    private String structuredSnapshot;

    @Column(name = "document_version_id")
    private UUID documentVersionId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "document_sha256", columnDefinition = "char(64)")
    private String documentSha256;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, columnDefinition = "char(64)")
    private String contentSha256;

    protected AgreementVersion() {} // required by JPA

    public AgreementVersion(UUID id, UUID tenantId, UUID agreementId, int versionNumber,
                             AgreementRecordMode recordMode, UUID submittedBy, Instant submittedAt,
                             UUID lastEditedBy, String structuredSnapshot, UUID documentVersionId,
                             String documentSha256, String contentSha256) {
        this.id = id;
        this.tenantId = tenantId;
        this.agreementId = agreementId;
        this.versionNumber = versionNumber;
        this.recordMode = recordMode;
        this.submittedBy = submittedBy;
        this.submittedAt = submittedAt;
        this.lastEditedBy = lastEditedBy;
        this.structuredSnapshot = structuredSnapshot;
        this.documentVersionId = documentVersionId;
        this.documentSha256 = documentSha256;
        this.contentSha256 = contentSha256;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgreementId() { return agreementId; }
    public int getVersionNumber() { return versionNumber; }
    public AgreementRecordMode getRecordMode() { return recordMode; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getLastEditedBy() { return lastEditedBy; }
    public String getStructuredSnapshot() { return structuredSnapshot; }
    public UUID getDocumentVersionId() { return documentVersionId; }
    public String getDocumentSha256() { return documentSha256; }
    public String getContentSha256() { return contentSha256; }
}
