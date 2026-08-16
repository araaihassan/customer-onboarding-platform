package co.ara.onboarding.authz;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite key for {@link UserRole}, matching user_role's PRIMARY KEY
 * (user_id, role_id). tenant_id is deliberately not part of the key: user_id is
 * already tenant-unique, so including it would allow the same assignment to be
 * written twice under different tenants.
 */
public class UserRoleId implements Serializable {

    private UUID userId;
    private UUID roleId;

    public UserRoleId() {}

    public UserRoleId(UUID userId, UUID roleId) {
        this.userId = userId;
        this.roleId = roleId;
    }

    public UUID getUserId() { return userId; }
    public UUID getRoleId() { return roleId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId other)) return false;
        return Objects.equals(userId, other.userId) && Objects.equals(roleId, other.roleId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, roleId);
    }
}
