package co.ara.onboarding.authz;

import co.ara.onboarding.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "role_grant")
public class RoleGrant extends TenantScopedEntity {

    @ManyToOne
    @JoinColumn(name = "role_id", nullable = false)
    private Role role;

    @Column(name = "permission_key", nullable = false)
    private String permissionKey;

    /**
     * STRING, not ORDINAL. The scope column is read by humans auditing grants and
     * by the predicate builder (Task 13); an ordinal would silently re-map every
     * stored grant if a value were ever inserted into the middle of the Scope
     * enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope;

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }

    public Scope getScope() { return scope; }
    public void setScope(Scope scope) { this.scope = scope; }
}
