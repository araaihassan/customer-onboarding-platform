package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.authz.UserRoleDirectory;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The escalation documented at the close of sub-project 1: a user.manage holder
 * reaches only users inside their own scope (AuthorizedQuery), but nothing checked
 * the role being handed out -- so at DEPARTMENT or TEAM scope they could assign any
 * role in the tenant, including one wider than their own authority, to anyone they
 * manage, themselves included.
 *
 * Through MockMvc against the real endpoint, like every other class in this
 * package: a service-level test calling RoleService.assignRole directly would
 * prove the guard exists but not that UserAdminController actually delegates to
 * it, and "a controller that forgets to delegate" is exactly what
 * SecurityTestBase exists to catch.
 */
class DelegationGuardTest extends SecurityTestBase {

    @Autowired AppUserRepository users;
    @Autowired UserRoleDirectory assignments;

    /**
     * The escalation sub-project 1 shipped: a DEPARTMENT-scoped user.manage holder
     * assigning a role that grants more than they hold. The target user is inside
     * their scope, so the AuthorizedQuery resolution added in sub-project 1 passes --
     * the hole was never about reaching the user, it was about the role.
     */
    @Test
    void narrowUserManageCannotAssignAWiderRole() throws Exception {
        UUID tenant = fixture.createTenant("delegate-wide");
        var manager = new AtomicReference<AppUser>();
        var target = new AtomicReference<UUID>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID managerId = fixture.createUserInDepartment(tenant, "mgr@example.com", dept);
            target.set(fixture.createUserInDepartment(tenant, "tgt@example.com", dept));
            grantRole(managerId, Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide", "", Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            manager.set(users.findById(managerId).orElseThrow());
        });

        String body = mvc.perform(as(assignRoleRequest("delegate-wide", target.get(), wideRole.get()),
                        manager.get()))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("customer.view");
    }

    @Test
    void assigningARoleTheCallerFullyHoldsIsAllowed() throws Exception {
        UUID tenant = fixture.createTenant("delegate-equal");
        var manager = new AtomicReference<AppUser>();
        var target = new AtomicReference<UUID>();
        var sameRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID managerId = fixture.createUserInDepartment(tenant, "mgr2@example.com", dept);
            target.set(fixture.createUserInDepartment(tenant, "tgt2@example.com", dept));
            grantRole(managerId, Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            sameRole.set(roles.createRole("Same", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT)));
            manager.set(users.findById(managerId).orElseThrow());
        });

        mvc.perform(as(assignRoleRequest("delegate-equal", target.get(), sameRole.get()), manager.get()))
           .andExpect(status().isNoContent());

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
    void incomparableScopesAreRefusedRatherThanRanked() throws Exception {
        UUID tenant = fixture.createTenant("delegate-sideways");
        var manager = new AtomicReference<AppUser>();
        var target = new AtomicReference<UUID>();
        var teamRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID managerId = fixture.createUserInDepartment(tenant, "mgr3@example.com", dept);
            target.set(fixture.createUserInDepartment(tenant, "tgt3@example.com", dept));
            grantRole(managerId, Map.of(
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT,
                    PermissionKeys.CUSTOMER_VIEW, Scope.DEPARTMENT));
            teamRole.set(roles.createRole("Teamish", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM)));
            manager.set(users.findById(managerId).orElseThrow());
        });

        mvc.perform(as(assignRoleRequest("delegate-sideways", target.get(), teamRole.get()), manager.get()))
           .andExpect(status().isBadRequest());
    }

    /**
     * The self-escalation case, which is what makes this a privilege escalation
     * rather than a delegation quirk: nothing stops the manager naming themselves.
     */
    @Test
    void aManagerCannotWidenTheirOwnAuthority() throws Exception {
        UUID tenant = fixture.createTenant("delegate-self");
        var manager = new AtomicReference<AppUser>();
        var wideRole = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID managerId = fixture.createUserInDepartment(tenant, "self@example.com", dept);
            grantRole(managerId, Map.of(PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            wideRole.set(roles.createRole("Wide2", "", Map.of(PermissionKeys.ROLE_MANAGE, Scope.ALL)));
            manager.set(users.findById(managerId).orElseThrow());
        });

        mvc.perform(as(assignRoleRequest("delegate-self", manager.get().getId(), wideRole.get()),
                        manager.get()))
           .andExpect(status().isBadRequest());
    }

    /** POST .../admin/users/{id}/roles, exactly what UserAdminController exposes. */
    private MockHttpServletRequestBuilder assignRoleRequest(String slug, UUID userId, UUID roleId) {
        return post("/api/t/" + slug + "/admin/users/" + userId + "/roles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roleId\":\"" + roleId + "\"}");
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
