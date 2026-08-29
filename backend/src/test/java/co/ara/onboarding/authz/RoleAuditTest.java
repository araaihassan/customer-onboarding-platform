package co.ara.onboarding.authz;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three write paths CLAUDE.md names as still unaudited, all in
 * authz/auth rather than the domain modules: re-enabling a disabled role
 * (setEnabled recorded only its disable branch), deleteRole (recorded
 * nothing at all), and PasswordResetService (covered separately in
 * PasswordResetAuditTest). Every action here is compliance-only
 * (timelineVisible = false), matching every other identity/auth event.
 */
class RoleAuditTest extends PostgresTestBase {

    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired AuditEventRepository auditEvents;

    /**
     * setEnabled already recorded role.disabled on the disable branch; this is
     * the missing other half. A single key with a boolean payload would have
     * made pre-2026-08-16-style history unqueryable, so this must be its own
     * distinct action key, not a flag on role.disabled.
     */
    @Test
    void reEnablingADisabledRoleWritesATimelineInvisibleAuditEvent() {
        UUID tenant = fixture.createTenant("role-audit-enable");
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            roleId.set(roles.createRole("Disable Me", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL)));
            roles.setEnabled(roleId.get(), false);
            roles.setEnabled(roleId.get(), true);
        });

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("role.enabled:" + roleId.get());

            assertThat(auditEvents.findAll())
                    .filteredOn(e -> "role.enabled".equals(e.getAction()))
                    .as("identity/auth administration is compliance-only, not customer-facing")
                    .allMatch(e -> !e.isTimelineVisible());
        });
    }

    /**
     * deleteRole recorded nothing at all before this task. The name must be
     * captured before the delete, since the row will not exist to read from
     * afterward -- deleteRole reads it first, matching setEnabled's own style.
     */
    @Test
    void deletingARoleWritesATimelineInvisibleAuditEvent() {
        UUID tenant = fixture.createTenant("role-audit-delete");
        var roleId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            roleId.set(roles.createRole("Delete Me", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL)));
            roles.deleteRole(roleId.get());
        });

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("role.deleted:" + roleId.get());

            assertThat(auditEvents.findAll())
                    .filteredOn(e -> "role.deleted".equals(e.getAction()))
                    .as("identity/auth administration is compliance-only, not customer-facing")
                    .allMatch(e -> !e.isTimelineVisible());
        });
    }
}
