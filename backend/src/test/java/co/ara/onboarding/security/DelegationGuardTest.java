package co.ara.onboarding.security;

import co.ara.onboarding.authz.InvalidGrantException;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.authz.UserRoleDirectory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The escalation documented at the close of sub-project 1: a user.manage holder
 * reaches only users inside their own scope (AuthorizedQuery), but nothing checked
 * the role being handed out -- so at DEPARTMENT or TEAM scope they could assign any
 * role in the tenant, including one wider than their own authority, to anyone they
 * manage, themselves included.
 */
class DelegationGuardTest extends SecurityTestBase {

    @Autowired UserRoleDirectory assignments;

    /**
     * The escalation sub-project 1 shipped: a DEPARTMENT-scoped user.manage holder
     * assigning a role that grants more than they hold. The target user is inside
     * their scope, so the AuthorizedQuery resolution added in sub-project 1 passes --
     * the hole was never about reaching the user, it was about the role.
     */
    @Test
    void narrowUserManageCannotAssignAWiderRole() {
        UUID tenant = fixture.createTenant("delegate-wide");
        var departmentId = new AtomicReference<UUID>();
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            departmentId.set(fixture.createDepartment(tenant, "Ops"));
            manager.set(fixture.createUserInDepartment(tenant, "mgr@example.com", departmentId.get()));
            target.set(fixture.createUserInDepartment(tenant, "tgt@example.com", departmentId.get()));
            grantRole(manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide", "", Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(target.get(), wideRole.get())))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("customer.view");
    }

    @Test
    void assigningARoleTheCallerFullyHoldsIsAllowed() {
        UUID tenant = fixture.createTenant("delegate-equal");
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var sameRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "mgr2@example.com", dept));
            target.set(fixture.createUserInDepartment(tenant, "tgt2@example.com", dept));
            grantRole(manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            sameRole.set(roles.createRole("Same", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT)));
        });

        fixture.runAsUser(tenant, manager.get(), () -> roles.assignRole(target.get(), sameRole.get()));

        fixture.runAs(tenant, () -> {
            Set<UUID> held = assignments.roleIdsByUser(Set.of(target.get()))
                    .getOrDefault(target.get(), Set.of());
            assertThat(held).contains(sameRole.get());
        });
    }

    /**
     * DEPARTMENT and TEAM are not comparable, and the guard must not invent an
     * ordering between them. A DEPARTMENT holder assigning a TEAM grant of the same
     * permission is refused -- conservative, and the only answer that cannot be wrong
     * in one direction or the other.
     */
    @Test
    void incomparableScopesAreRefusedRatherThanRanked() {
        UUID tenant = fixture.createTenant("delegate-sideways");
        var manager = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();
        var teamRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "mgr3@example.com", dept));
            target.set(fixture.createUserInDepartment(tenant, "tgt3@example.com", dept));
            grantRole(manager.get(), Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            teamRole.set(roles.createRole("Teamish", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(target.get(), teamRole.get())))
                .isInstanceOf(InvalidGrantException.class);
    }

    /**
     * The self-escalation case, which is what makes this a privilege escalation
     * rather than a delegation quirk: nothing stops the manager naming themselves.
     */
    @Test
    void aManagerCannotWidenTheirOwnAuthority() {
        UUID tenant = fixture.createTenant("delegate-self");
        var manager = new AtomicReference<UUID>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            manager.set(fixture.createUserInDepartment(tenant, "self@example.com", dept));
            grantRole(manager.get(), Map.of(PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide2", "", Map.of(PermissionKeys.ROLE_MANAGE, Scope.ALL)));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, manager.get(),
                () -> roles.assignRole(manager.get(), wideRole.get())))
                .isInstanceOf(InvalidGrantException.class);
    }

    /**
     * No such fixture helper exists yet, and none of the other security tests
     * needed one: they only ever create a role and assign it while running as the
     * tenant administrator. This test is the first to need "create this role and
     * hand it to a narrow-scoped actor" as a single setup step, so it stays local
     * rather than growing TenantFixture for one caller.
     */
    private void grantRole(UUID userId, Map<String, Scope> grants) {
        UUID roleId = roles.createRole("Grant-" + userId, "", grants);
        roles.assignRole(userId, roleId);
    }
}
