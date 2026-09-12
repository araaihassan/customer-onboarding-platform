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
     *
     * Asserts the EXACT key set, not spot-checks: a map widened with a third key
     * would still satisfy "DOCUMENT_VIEW and DOCUMENT_UPLOAD are present" but must
     * fail this containsExactlyInAnyOrder.
     *
     * case.view, programme.view and plan.approve_schedule are explicitly asserted
     * ABSENT, not merely omitted from the positive list -- this is a regression
     * guard for a real finding: an earlier version of PortalPermissions granted
     * all three at ALL on the mistaken assumption that DocumentAudienceFilter's
     * narrowing covered them. It does not, and never will (that filter only ever
     * covers Document); Case, Programme and PlanRevision have no AudienceFilter
     * anywhere in this plan, so at ALL scope a portal contact of one customer
     * could reach another customer's case, programme or schedule-approval
     * decision. See PortalPermissions' own javadoc for the full finding.
     */
    @Test
    void aPortalActorResolvesExactlyTheExpectedConstantSet() {
        UUID tenantId = fixture.createTenant("portal-authority");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null, null));
        UUID contactUserId = fixture.createPortalUserForContact(tenantId, customerId, "contact@acme.test");

        fixture.runAsUser(tenantId, contactUserId, () -> {
            var effective = authorization.effectivePermissions();

            assertThat(effective.byPermission().keySet())
                    .containsExactlyInAnyOrder(PermissionKeys.DOCUMENT_VIEW, PermissionKeys.DOCUMENT_UPLOAD);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_UPLOAD)).containsExactly(Scope.ALL);

            // Never granted -- no narrowing mechanism exists for any of these three.
            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.PROGRAMME_VIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.PLAN_APPROVE_SCHEDULE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_REVIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.USER_MANAGE)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.ROLE_MANAGE)).isEmpty();
        });
    }

    /**
     * forSponsor() is identical to forContact() today (PortalPermissions' own
     * javadoc says so, deliberately -- sub-project 7 is where a sponsor's real
     * additive authority gets built, with its own narrowing mechanism). Without
     * this test, a primaryContact=true contact is never exercised anywhere in the
     * suite -- every other case here uses the 3-arg createPortalUserForContact
     * overload, which defaults primaryContact to false -- so a future addition
     * to forSponsor() alone would ship with no test covering the branch that
     * calls it.
     */
    @Test
    void aSponsorContactResolvesTheSameSetAsAPlainContactToday() {
        UUID tenantId = fixture.createTenant("portal-sponsor");
        UUID customerId = fixture.runAsReturning(tenantId,
                () -> fixture.createCustomer(tenantId, "Acme", null, null, null));
        UUID sponsorUserId = fixture.createPortalUserForContact(
                tenantId, customerId, "sponsor@acme.test", true);

        fixture.runAsUser(tenantId, sponsorUserId, () -> {
            var effective = authorization.effectivePermissions();

            assertThat(effective.byPermission().keySet())
                    .containsExactlyInAnyOrder(PermissionKeys.DOCUMENT_VIEW, PermissionKeys.DOCUMENT_UPLOAD);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_VIEW)).containsExactly(Scope.ALL);
            assertThat(effective.scopesFor(PermissionKeys.DOCUMENT_UPLOAD)).containsExactly(Scope.ALL);

            assertThat(effective.scopesFor(PermissionKeys.CASE_VIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.PROGRAMME_VIEW)).isEmpty();
            assertThat(effective.scopesFor(PermissionKeys.PLAN_APPROVE_SCHEDULE)).isEmpty();
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
