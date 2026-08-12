package co.ara.onboarding.identity;

import co.ara.onboarding.platform.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

// Deliberately extends BaseEntity, not TenantScopedEntity: platform admins are
// vendor-side administration and are not scoped to any tenant (spec 5.2).
@Entity
@Table(name = "platform_admin")
public class PlatformAdmin extends BaseEntity {

    @Column(nullable = false) private String email;
    @Column(name = "password_hash", nullable = false) private String passwordHash;
    @Column(name = "full_name", nullable = false) private String fullName;
    @Column(nullable = false) private boolean enabled = true;

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
