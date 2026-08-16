package co.ara.onboarding.identity;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrgStructureService had no tests at all, and no audit events either — departments
 * and teams were created with nothing recorded anywhere. Creating them is spec 12's
 * definition-of-done clause 2 ("a tenant administrator manages users, roles,
 * departments and teams"), and admin.spec.ts drives it live, so the operation was
 * exercised end to end while leaving no trace.
 */
class OrgStructureServiceTest extends PostgresTestBase {

    @Autowired OrgStructureService org;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired AuditEventRepository auditEvents;

    /** Grants both org permissions; they are ALL-only, so scope never varies. */
    private UUID adminIn(UUID tenant, String email) {
        var admin = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, email));
            UUID role = roles.createRole("Org Admin", "", Map.of(
                    PermissionKeys.DEPARTMENT_MANAGE, Scope.ALL,
                    PermissionKeys.TEAM_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });
        return admin.get();
    }

    @Test
    void creatingADepartmentIsAudited() {
        UUID tenant = fixture.createTenant("org-dept-audit");
        UUID admin = adminIn(tenant, "orgdept@example.com");
        var departmentId = new AtomicReference<UUID>();

        fixture.runAsUser(tenant, admin, () ->
            departmentId.set(org.createDepartment(
                    new OrgStructureService.DepartmentRequest("Onboarding", "Runs delivery")).id()));

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("department.created:" + departmentId.get()));
    }

    @Test
    void creatingATeamIsAudited() {
        UUID tenant = fixture.createTenant("org-team-audit");
        UUID admin = adminIn(tenant, "orgteam@example.com");
        var teamId = new AtomicReference<UUID>();

        fixture.runAsUser(tenant, admin, () -> {
            UUID department = org.createDepartment(
                    new OrgStructureService.DepartmentRequest("Delivery", null)).id();
            teamId.set(org.createTeam(
                    new OrgStructureService.TeamRequest("Pod A", "First pod", department)).id());
        });

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("team.created:" + teamId.get()));
    }

    /**
     * Org structure is internal tenant administration, so it stays off the
     * customer-visible timeline for the same reason user.* does.
     */
    @Test
    void orgAuditEventsAreNotTimelineVisible() {
        UUID tenant = fixture.createTenant("org-flag-audit");
        UUID admin = adminIn(tenant, "orgflag@example.com");

        fixture.runAsUser(tenant, admin, () -> {
            UUID department = org.createDepartment(
                    new OrgStructureService.DepartmentRequest("Support", null)).id();
            org.createTeam(new OrgStructureService.TeamRequest("Pod B", null, department));
        });

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> e.getAction().startsWith("department.")
                            || e.getAction().startsWith("team."))
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.isTimelineVisible()).isFalse()));
    }

    /**
     * The gate is real, not decorative — the audit work above must not be read as
     * evidence that these methods are reachable without the permission.
     */
    @Test
    void creatingADepartmentRequiresDepartmentManage() {
        UUID tenant = fixture.createTenant("org-dept-denied");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "nodept@example.com"));
            UUID role = roles.createRole("Team Only", "", Map.of(
                    PermissionKeys.TEAM_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> org.createDepartment(
                        new OrgStructureService.DepartmentRequest("Nope", null))))
                .isInstanceOf(AccessDeniedException.class);
    }
}
