package co.ara.onboarding.identity;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser extends TenantScopedEntity {

    @Column(nullable = false) private String email;
    @Column(name = "password_hash") private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false) private UserType userType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private UserStatus status;

    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(name = "department_id") private UUID departmentId;
    @Column(name = "mfa_enabled", nullable = false) private boolean mfaEnabled = false;
    @Column(name = "mfa_secret") private String mfaSecret;
    @Column(name = "last_login_at") private Instant lastLoginAt;

    // team_member.tenant_id is NOT NULL and RLS-checked (V4__identity.sql), but a
    // single-join-column mapping leaves Hibernate no way to populate it -- its
    // generated INSERT would only ever set user_id and team_id. Declaring
    // tenant_id as a second join column, mapped back to the owning AppUser's own
    // tenant_id column, makes Hibernate populate it from the owner on every
    // insert. Verified empirically: without this, INSERT INTO team_member
    // (user_id, team_id) VALUES (?, ?) fails RLS's WITH CHECK (tenant_id is left
    // NULL); with it, the generated SQL includes tenant_id and the row persists.
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_member", joinColumns = {
            @JoinColumn(name = "user_id", referencedColumnName = "id"),
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id")
    })
    @Column(name = "team_id")
    private Set<UUID> teamIds = new HashSet<>();

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public UserType getUserType() { return userType; }
    public void setUserType(UserType userType) { this.userType = userType; }
    public UserStatus getStatus() { return status; }
    public void setStatus(UserStatus status) { this.status = status; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public UUID getDepartmentId() { return departmentId; }
    public void setDepartmentId(UUID departmentId) { this.departmentId = departmentId; }
    public boolean isMfaEnabled() { return mfaEnabled; }
    public void setMfaEnabled(boolean mfaEnabled) { this.mfaEnabled = mfaEnabled; }
    public String getMfaSecret() { return mfaSecret; }
    public void setMfaSecret(String mfaSecret) { this.mfaSecret = mfaSecret; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public Set<UUID> getTeamIds() { return teamIds; }
    public void setTeamIds(Set<UUID> teamIds) { this.teamIds = teamIds; }
}
