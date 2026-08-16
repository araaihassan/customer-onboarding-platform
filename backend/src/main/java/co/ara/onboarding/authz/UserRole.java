package co.ara.onboarding.authz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Assignment of a role to a user.
 *
 * Deliberately NOT a TenantScopedEntity subclass. That superclass chain supplies
 * id, created_at and updated_at, and user_role has none of those columns -- it is
 * a pure join table keyed on (user_id, role_id).
 *
 * The consequence is that this entity carries no Hibernate tenantFilter, so
 * tenant isolation for user_role rests on the database: V7 calls
 * enable_tenant_rls('user_role'), which is FORCED and deny-by-default when no
 * tenant GUC is set. Isolation here is enforced once rather than twice, so
 * tenant_id must be set explicitly on every write -- the constructor requires it
 * rather than leaving it to a caller to remember, and RLS's WITH CHECK rejects
 * the insert outright if it disagrees with the bound tenant.
 */
@Entity
@Table(name = "user_role")
@IdClass(UserRoleId.class)
public class UserRole {

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Id
    @Column(name = "role_id", nullable = false)
    private UUID roleId;

    protected UserRole() {}   // required by JPA

    public UserRole(UUID tenantId, UUID userId, UUID roleId) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.roleId = roleId;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }
}
