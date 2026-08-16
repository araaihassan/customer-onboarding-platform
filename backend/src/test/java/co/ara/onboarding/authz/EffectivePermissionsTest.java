package co.ara.onboarding.authz;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * createUser runs inside runAs throughout: app_user is RLS-protected, and a
 * repository save outside a bound tenant fails the policy's WITH CHECK.
 */
class EffectivePermissionsTest extends PostgresTestBase {

    @Autowired AuthorizationService authorization;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void multipleRolesUnionTheirScopes() {
        UUID tenant = fixture.createTenant("union-test");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "multi@example.com"));
            UUID teamRole = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            UUID assignedRole = roles.createRole("Own Records", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), teamRole);
            roles.assignRole(user.get(), assignedRole);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.scopesFor(PermissionKeys.CUSTOMER_VIEW))
                    .containsExactlyInAnyOrder(Scope.TEAM, Scope.ASSIGNED);
        });
    }

    @Test
    void disabledRoleContributesNothing() {
        UUID tenant = fixture.createTenant("disabled-role");
        var user = new AtomicReference<UUID>();
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "disabled@example.com"));
            roleId.set(roles.createRole("Temp", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL)));
            roles.assignRole(user.get(), roleId.get());
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_VIEW)).isTrue());

        fixture.runAs(tenant, () -> roles.setEnabled(roleId.get(), false));

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_VIEW))
                .as("a disabled role must contribute nothing immediately")
                .isFalse());
    }

    @Test
    void revokedGrantTakesEffectOnTheNextRequest() {
        UUID tenant = fixture.createTenant("revoke-test");
        var user = new AtomicReference<UUID>();
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "revoked@example.com"));
            roleId.set(roles.createRole("Editor", "",
                    Map.of(PermissionKeys.CUSTOMER_EDIT, Scope.ALL)));
            roles.assignRole(user.get(), roleId.get());
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_EDIT)).isTrue());

        fixture.runAs(tenant, () -> roles.updateGrants(roleId.get(), Map.of()));

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions()
                    .has(PermissionKeys.CUSTOMER_EDIT))
                .as("no cross-request permission cache (spec 6.7)")
                .isFalse());
    }

    @Test
    void ungrantedPermissionYieldsEmptyScopeSet() {
        UUID tenant = fixture.createTenant("no-grant");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> user.set(fixture.createUser(tenant, "nothing@example.com")));

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions()
                    .scopesFor(PermissionKeys.CUSTOMER_VIEW)).isEmpty());
    }

    /**
     * Not in the plan. The memoization in AuthorizationService is what makes the
     * two "next request" assertions above meaningful, and nothing verified that it
     * memoizes at all — if effectivePermissions() re-queried on every call the
     * tests would still pass, and if it cached in a singleton they would still pass
     * within a single request. This pins both halves: stable within a request,
     * because that is the memo's purpose, and re-resolved across requests, because
     * that is what forbids stale authority (spec 6.7).
     */
    @Test
    void permissionsAreMemoizedWithinARequestButNotAcrossThem() {
        UUID tenant = fixture.createTenant("memo-scope");
        var user = new AtomicReference<UUID>();
        var firstInstance = new AtomicReference<EffectivePermissions>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "memo@example.com"));
            UUID role = roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var first = authorization.effectivePermissions();
            var second = authorization.effectivePermissions();
            assertThat(second)
                    .as("within one request the resolved permissions are reused")
                    .isSameAs(first);
            firstInstance.set(first);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorization.effectivePermissions())
                    .as("a new request must resolve afresh, not reuse the previous instance")
                    .isNotSameAs(firstInstance.get()));
    }
}
