package co.ara.onboarding.security;

import co.ara.onboarding.authz.AuthorizationService;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import co.ara.onboarding.authz.InvalidGrantException;
import co.ara.onboarding.authz.RoleService;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortalAuthorityTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired AuthorizationService authorization;
    @Autowired RoleService roleService;

    /**
     * The constant set is pinned so that widening it is a deliberate edit with a
     * failing test attached. A portal actor resolves document.view at ALL and is
     * narrowed entirely by DocumentAudienceFilter -- so an accidental addition
     * here is an accidental grant to every external user in every tenant.
     */
    @Test
    void aPortalActorResolvesExactlyTheExpectedConstantSet() {
        UUID tenantId = fixture.createTenant("portal-authority");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "sponsor@acme.test");

        fixture.runAsUser(tenantId, contactUserId, () -> {
            var effective = authorization.effectivePermissions();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_UPLOAD)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).containsExactly(Scope.ALL);
            // Not granted, and must never be:
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_REVIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.USER_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.ROLE_MANAGE)).isEmpty();
        });
    }

    /**
     * A deactivated contact resolves nothing, on the very next request -- the
     * same property AuthorizationService already guarantees for a deactivated
     * internal user by joining app_user on status = 'ACTIVE'.
     */
    @Test
    void aRetiredContactResolvesNothing() {
        UUID tenantId = fixture.createTenant("portal-retired");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "gone@acme.test");
        fixture.retireContactFor(tenantId, contactUserId);

        fixture.runAsUser(tenantId, contactUserId, () ->
                assertThat(authorization.effectivePermissions()
                        .scopesFor(PermissionKeys.DOCUMENT_VIEW)).isEmpty());
    }

    /** RoleService's refusal is untouched by this design and must stay refused. */
    @Test
    void aPortalUserStillCannotBeAssignedAnInternalRole() {
        // Not "portal-role" -- DirectApiAccessTest already claims that slug, and
        // tenant slugs must be unique across the whole suite.
        UUID tenantId = fixture.createTenant("portal-role-assign");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "role@acme.test");
        UUID adminId = fixture.createAdministrator(tenantId, "admin@portal.test");
        UUID roleId = fixture.administratorRoleId(tenantId);

        // isInstanceOf, not hasRootCauseInstanceOf: assignRole throws
        // InvalidGrantException directly (no wrapping cause), and
        // runAsUser/runUnauthenticated's TransactionTemplate rethrows it as-is
        // rather than wrapping it -- confirmed empirically; the brief's own
        // hasRootCauseInstanceOf sample fails with "current throwable has no
        // cause" against the real exception shape.
        assertThatThrownBy(() -> fixture.runAsUser(tenantId, adminId,
                () -> roleService.assignRole(contactUserId, roleId)))
                .isInstanceOf(InvalidGrantException.class);
    }
}
