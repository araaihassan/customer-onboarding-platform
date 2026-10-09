package co.ara.onboarding.security;

import co.ara.onboarding.authz.*;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SystemActorTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AuthorizationService authorization;
    @Autowired AuthContextProvider contexts;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired PortalContactDirectory contacts;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void theSystemActorHoldsExactlyTheJobPermissionsAtAll() {
        UUID tenant = fixture.createTenant("sys-perms");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(tenant));
        fixture.runUnauthenticated(tenant, () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.byPermission().keySet()).containsExactlyInAnyOrder(
                    PermissionKeys.CASE_VIEW, PermissionKeys.TASK_VIEW, PermissionKeys.SLA_VIEW,
                    PermissionKeys.DOCUMENT_REQUEST);
            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.TASK_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.SLA_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_REQUEST)).containsExactly(Scope.ALL);
            var ctx = contexts.current();
            assertThat(ctx.userType()).isEqualTo(UserType.SYSTEM);
            assertThat(ctx.tenantId()).isEqualTo(tenant);
            assertThat(ctx.userId()).isEqualTo(SystemPrincipal.SYSTEM_USER_ID);
            assertThat(ctx.departmentId()).isNull();
            assertThat(ctx.teamIds()).isEmpty();
        });
    }

    @Test
    void theSystemPrincipalIsBoundToItsOwnTenant() {
        UUID a = fixture.createTenant("sys-a");
        UUID b = fixture.createTenant("sys-b");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(a));
        fixture.runUnauthenticated(b, () ->
                assertThat(authorization.effectivePermissions().byPermission()).isEmpty());
    }

    @Test
    void noJwtCanCarryTheSystemUserId() {
        // The JWT filter builds AuthenticatedPrincipal, never SystemPrincipal; the nil UUID is not
        // a user id any app_user row can hold (Uuid7 never generates it).
        assertThat(SystemPrincipal.SYSTEM_USER_ID).isEqualTo(new UUID(0, 0));
        assertThat(SystemPrincipal.class).isNotEqualTo(AuthenticatedPrincipal.class);
    }

    @Test
    void aSystemPrincipalIsNotAnAuthenticatedPrincipalSoNoUserLookupCanResolveIt() {
        UUID tenant = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(SystemPrincipal.authentication(tenant));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.security.access.AccessDeniedException.class, () -> contexts.principal());
    }

    @Test
    void theDatabaseRefusesAUserRowTypedSystem() {
        UUID tenant = fixture.createTenant("sys-check");
        UUID[] holder = new UUID[1];
        fixture.runUnauthenticated(tenant, () -> holder[0] = fixture.createUser(tenant, "u@sys-check.test"));
        UUID user = holder[0];
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                ownerJdbc().update("UPDATE app_user SET user_type = 'SYSTEM' WHERE id = ?", user))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void aRequestBorneIdentityWhoseUserRowReportsSystemResolvesNoAuthority() {
        UUID tenant = UUID.randomUUID();
        ActorDirectory lyingDirectory = id -> java.util.Optional.of(new AuthContext(
                tenant, id, UserType.SYSTEM, null, java.util.Set.of()));
        var provider = new AuthContextProvider(lyingDirectory);
        var service = new AuthorizationService(jdbc, provider, contacts);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        new AuthenticatedPrincipal(tenant, UUID.randomUUID()), null, java.util.List.of()));
        fixture.runUnauthenticated(tenant, () ->
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.security.access.AccessDeniedException.class,
                        service::effectivePermissions));
    }
}
