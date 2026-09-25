package co.ara.onboarding.agreement;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One party expected to sign an {@link Agreement} -- an external customer
 * contact or an internal app_user, never both ({@code agreement_signatory_party_ck}).
 * Mutable and DELETE-able (unlike the three append-only evidence tables below):
 * {@code PUT .../signatories} replaces a DRAFT agreement's whole list, and a
 * signatory row is not itself business evidence -- the frozen copy a signature is
 * proven against lives in {@code agreement_version.structured_snapshot}.
 */
@Entity
@Table(name = "agreement_signatory")
public class AgreementSignatory {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agreement_id", nullable = false)
    private UUID agreementId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SignatoryKind kind;

    @Column(name = "contact_id")
    private UUID contactId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "display_role", nullable = false)
    private String displayRole;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }

    public UUID getAgreementId() { return agreementId; }
    public void setAgreementId(UUID agreementId) { this.agreementId = agreementId; }

    public SignatoryKind getKind() { return kind; }
    public void setKind(SignatoryKind kind) { this.kind = kind; }

    public UUID getContactId() { return contactId; }
    public void setContactId(UUID contactId) { this.contactId = contactId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getDisplayRole() { return displayRole; }
    public void setDisplayRole(String displayRole) { this.displayRole = displayRole; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
