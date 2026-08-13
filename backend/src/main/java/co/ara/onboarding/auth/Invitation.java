package co.ara.onboarding.auth;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitation")
public class Invitation extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvitationPurpose purpose;

    @Column(name = "customer_contact_id")
    private UUID customerContactId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    /** Unredeemed, unrevoked, and not yet expired. All three, for the stated purpose. */
    public boolean isRedeemable(InvitationPurpose expected, Instant now) {
        return purpose == expected
                && acceptedAt == null
                && revokedAt == null
                && expiresAt.isAfter(now);
    }

    public InvitationPurpose getPurpose() { return purpose; }
    public void setPurpose(InvitationPurpose purpose) { this.purpose = purpose; }

    public UUID getCustomerContactId() { return customerContactId; }
    public void setCustomerContactId(UUID customerContactId) { this.customerContactId = customerContactId; }

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}
