package co.ara.onboarding.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One signatory's recorded signature against a specific {@link AgreementVersion}
 * -- append-only evidence, the same shape as {@link AgreementVersion}: one
 * all-args constructor, no setters, {@code GRANT SELECT, INSERT} only (V25).
 * At most one per (agreement, signatory) -- re-recording a signature is not a
 * concept this sub-project supports.
 */
@Entity
@Table(name = "agreement_signature")
public class AgreementSignature {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Column(name = "signatory_id", nullable = false)
    private UUID signatoryId;

    @Column(name = "agreement_version_id", nullable = false)
    private UUID agreementVersionId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "signed_content_sha256", nullable = false, columnDefinition = "char(64)")
    private String signedContentSha256;

    @Column(name = "signed_on", nullable = false)
    private LocalDate signedOn;

    @Column(nullable = false)
    private String method;

    @Column(name = "recorded_by", nullable = false)
    private UUID recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "countersigned_document_version_id")
    private UUID countersignedDocumentVersionId;

    protected AgreementSignature() {} // required by JPA

    public AgreementSignature(UUID id, UUID tenantId, UUID agreementId, UUID signatoryId,
                               UUID agreementVersionId, String signedContentSha256, LocalDate signedOn,
                               String method, UUID recordedBy, Instant recordedAt,
                               UUID countersignedDocumentVersionId) {
        this.id = id;
        this.tenantId = tenantId;
        this.agreementId = agreementId;
        this.signatoryId = signatoryId;
        this.agreementVersionId = agreementVersionId;
        this.signedContentSha256 = signedContentSha256;
        this.signedOn = signedOn;
        this.method = method;
        this.recordedBy = recordedBy;
        this.recordedAt = recordedAt;
        this.countersignedDocumentVersionId = countersignedDocumentVersionId;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgreementId() { return agreementId; }
    public UUID getSignatoryId() { return signatoryId; }
    public UUID getAgreementVersionId() { return agreementVersionId; }
    public String getSignedContentSha256() { return signedContentSha256; }
    public LocalDate getSignedOn() { return signedOn; }
    public String getMethod() { return method; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public UUID getCountersignedDocumentVersionId() { return countersignedDocumentVersionId; }
}
