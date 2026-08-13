package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every exception assertion here wraps fixture.runAs rather than sitting inside
 * its lambda. runAs executes in a TransactionTemplate, so catching the exception
 * inside the lambda leaves the transaction marked rollback-only while execution
 * continues to normal completion; the template then attempts a commit and throws
 * UnexpectedRollbackException, masking the exception under test. See Global
 * Constraints.
 */
class RoleServiceTest extends PostgresTestBase {

    @Autowired RoleService roles;
    @Autowired RoleRepository roleRepository;
    @Autowired TenantFixture fixture;

    @Test
    void rejectsScopeNotAllowedForPermission() {
        UUID tenant = fixture.createTenant("scope-check");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.createRole("Bad Role", "",
                Map.of(PermissionKeys.ROLE_MANAGE, Scope.TEAM))))
                .isInstanceOf(InvalidGrantException.class)
                .hasMessageContaining("role.manage")
                .hasMessageContaining("TEAM");
    }

    @Test
    void rejectsUnknownPermissionKey() {
        UUID tenant = fixture.createTenant("unknown-perm");

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.createRole("Bad Role", "",
                Map.of("not.a.permission", Scope.ALL))))
                .isInstanceOf(InvalidGrantException.class);
    }

    @Test
    void acceptsValidGrantAndPersistsScope() {
        UUID tenant = fixture.createTenant("valid-grant");

        fixture.runAs(tenant, () -> {
            UUID roleId = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            Role saved = roleRepository.findById(roleId).orElseThrow();
            assertThat(saved.getGrants()).hasSize(1);
            assertThat(saved.getGrants().iterator().next().getScope()).isEqualTo(Scope.TEAM);
        });
    }

    /**
     * Not in the plan, and the reason it is here: updateGrants is a public API
     * method with no coverage at all, and role_grant carries
     * UNIQUE (role_id, permission_key). Re-granting the SAME permission at a
     * different scope -- changing customer.view from TEAM to ALL, the ordinary
     * way a role gets edited -- is where clear()-then-add breaks, if Hibernate
     * orders the insert of the new row before the delete of the old one.
     */
    @Test
    void updateGrantsCanRescopeAnExistingPermission() {
        UUID tenant = fixture.createTenant("regrant");
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> roleId.set(roles.createRole("Rescope Me", "",
                Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM))));

        fixture.runAs(tenant, () ->
                roles.updateGrants(roleId.get(), Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));

        fixture.runAs(tenant, () -> {
            Role saved = roleRepository.findById(roleId.get()).orElseThrow();
            assertThat(saved.getGrants()).hasSize(1);
            assertThat(saved.getGrants().iterator().next().getScope()).isEqualTo(Scope.ALL);
        });
    }

    @Test
    void deletionIsBlockedWhileUsersAreAssigned() {
        UUID tenant = fixture.createTenant("role-delete");
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            roleId.set(roles.createRole("In Use", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            UUID userId = fixture.createUser(tenant, "holder@example.com");
            roles.assignRole(userId, roleId.get());
        });

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> roles.deleteRole(roleId.get())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("assigned");
    }
}
