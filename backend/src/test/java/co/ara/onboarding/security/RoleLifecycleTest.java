package co.ara.onboarding.security;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test 6 — disabled and deleted roles. */
class RoleLifecycleTest extends SecurityTestBase {

    /**
     * "On the very next request" is the assertion. Each MockMvc call is a fresh
     * request scope, so a cached AuthorizationService would keep the old authority
     * and this would pass while the guarantee was broken.
     */
    @Test
    void disablingARoleRemovesAuthorityImmediatelyAndReEnablingRestoresIt() throws Exception {
        UUID tenant = fixture.createTenant("role-disable");
        var admin = new AtomicReference<AppUser>();
        var viewer = new AtomicReference<AppUser>();
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "roleadmin@example.com"));
            AppUser v = fixture.createUserWithPassword(tenant, "viewer@example.com", "long-enough-password");
            viewer.set(v);
            roleId.set(roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            roles.assignRole(v.getId(), roleId.get());
        });

        mvc.perform(as(get("/api/t/role-disable/customers"), viewer.get()))
           .andExpect(status().isOk());

        mvc.perform(as(post("/api/t/role-disable/admin/roles/" + roleId.get() + "/disable"), admin.get()))
           .andExpect(status().isNoContent());

        mvc.perform(as(get("/api/t/role-disable/customers"), viewer.get()))
           .andExpect(status().isForbidden());

        mvc.perform(as(post("/api/t/role-disable/admin/roles/" + roleId.get() + "/enable"), admin.get()))
           .andExpect(status().isNoContent());

        mvc.perform(as(get("/api/t/role-disable/customers"), viewer.get()))
           .andExpect(status().isOk());
    }

    /**
     * 409 while held, then deletable once unassigned. Deleting a role somebody holds
     * would silently strip their authority with no record of why.
     */
    @Test
    void roleCannotBeDeletedWhileAssignedButCanBeAfterUnassigning() throws Exception {
        UUID tenant = fixture.createTenant("sec-role-delete");
        var admin = new AtomicReference<AppUser>();
        var holder = new AtomicReference<AppUser>();
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createAdminUser(tenant, "deladmin@example.com"));
            AppUser h = fixture.createUserWithPassword(tenant, "holder@example.com", "long-enough-password");
            holder.set(h);
            roleId.set(roles.createRole("In Use", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            roles.assignRole(h.getId(), roleId.get());
        });

        mvc.perform(as(delete("/api/t/sec-role-delete/admin/roles/" + roleId.get()), admin.get()))
           .andExpect(status().isConflict());

        mvc.perform(as(delete("/api/t/sec-role-delete/admin/users/" + holder.get().getId()
                        + "/roles/" + roleId.get()), admin.get()))
           .andExpect(status().isNoContent());

        mvc.perform(as(delete("/api/t/sec-role-delete/admin/roles/" + roleId.get()), admin.get()))
           .andExpect(status().isNoContent());
    }
}
