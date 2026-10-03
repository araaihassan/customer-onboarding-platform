package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.NoSuchElementException;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ReportingLinesTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired UserAdminService users;
    @Autowired OrgStructureService org;
    @Autowired co.ara.onboarding.authz.RoleService roles;

    @Test
    void aUserCanBeGivenAManagerAndTheViewCarriesIt() {
        UUID tenant = fixture.createTenant("rl-manager");
        var admin = fixture.createAdminUser(tenant, "admin@rl.test");
        UUID manager = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "boss@rl.test"));
        UUID report = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "report@rl.test"));
        fixture.runAsUser(tenant, admin.getId(), () -> {
            var view = users.update(report, new UserAdminService.UpdateUserRequest("Report", null, manager));
            assertThat(view.managerId()).isEqualTo(manager);
            assertThat(users.get(report).managerId()).isEqualTo(manager);
        });
    }

    @Test
    void aNullManagerOnUpdateClearsIt() {
        UUID tenant = fixture.createTenant("rl-clear");
        var admin = fixture.createAdminUser(tenant, "admin@clear.test");
        UUID manager = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "boss@clear.test"));
        UUID report = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "r@clear.test"));
        fixture.runAsUser(tenant, admin.getId(), () -> {
            users.update(report, new UserAdminService.UpdateUserRequest("R", null, manager));
            assertThat(users.update(report, new UserAdminService.UpdateUserRequest("R", null)).managerId()).isNull();
        });
    }

    @Test
    void aUserCannotManageThemselves() {
        UUID tenant = fixture.createTenant("rl-self");
        var admin = fixture.createAdminUser(tenant, "admin@self.test");
        UUID u = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "u@self.test"));
        assertThatThrownBy(() -> fixture.runAsUser(tenant, admin.getId(), () ->
                users.update(u, new UserAdminService.UpdateUserRequest("U", null, u))))
                .isInstanceOf(ReportingLineException.class);
    }

    @Test
    void aManagerFromAnotherTenantIsNotFound() {
        UUID mine = fixture.createTenant("rl-mine");
        UUID theirs = fixture.createTenant("rl-theirs");
        var admin = fixture.createAdminUser(mine, "admin@mine.test");
        UUID report = fixture.runAsReturning(mine, () -> fixture.createUser(mine, "r@mine.test"));
        UUID foreign = fixture.runAsReturning(theirs, () -> fixture.createUser(theirs, "x@theirs.test"));
        assertThatThrownBy(() -> fixture.runAsUser(mine, admin.getId(), () ->
                users.update(report, new UserAdminService.UpdateUserRequest("R", null, foreign))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aDepartmentCanBeGivenAHeadThroughAFullReplace() {
        UUID tenant = fixture.createTenant("rl-head");
        var admin = fixture.createAdminUser(tenant, "admin@head.test");
        UUID head = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "head@head.test"));
        fixture.runAsUser(tenant, admin.getId(), () -> {
            var created = org.createDepartment(new OrgStructureService.DepartmentRequest("Finance", "Money"));
            var updated = org.updateDepartment(created.id(),
                    new OrgStructureService.DepartmentRequest("Finance", "Money", head));
            assertThat(updated.headUserId()).isEqualTo(head);
            assertThat(updated.description()).isEqualTo("Money");
        });
    }

    @Test
    void aHeadFromAnotherTenantIsNotFound() {
        UUID mine = fixture.createTenant("rl-hmine");
        UUID theirs = fixture.createTenant("rl-htheirs");
        var admin = fixture.createAdminUser(mine, "admin@hmine.test");
        UUID foreign = fixture.runAsReturning(theirs, () -> fixture.createUser(theirs, "x@htheirs.test"));
        assertThatThrownBy(() -> fixture.runAsUser(mine, admin.getId(), () ->
                org.createDepartment(new OrgStructureService.DepartmentRequest("D", "d", foreign))))
                .isInstanceOf(NoSuchElementException.class);
    }

    // Narrowest-scope writes: user.manage / user.view at DEPARTMENT. department.manage is
    // ALL-only in the catalog, so resolveHead has no narrower scope to exercise.
    private UUID[] narrowWorld(UUID tenant) {
        return fixture.runAsReturning(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "A");
            UUID deptB = fixture.createDepartment(tenant, "B");
            UUID lead = fixture.createUserInDepartment(tenant, "lead@narrow.test", deptA);
            UUID peer = fixture.createUserInDepartment(tenant, "peer@narrow.test", deptA);
            UUID report = fixture.createUserInDepartment(tenant, "report@narrow.test", deptA);
            UUID outsider = fixture.createUserInDepartment(tenant, "out@narrow.test", deptB);
            UUID role = roles.createRole("Ops Lead", "", java.util.Map.of(
                    co.ara.onboarding.authz.PermissionKeys.USER_VIEW, co.ara.onboarding.authz.Scope.DEPARTMENT,
                    co.ara.onboarding.authz.PermissionKeys.USER_MANAGE, co.ara.onboarding.authz.Scope.DEPARTMENT));
            roles.assignRole(lead, role);
            return new UUID[] {deptA, lead, peer, report, outsider};
        });
    }

    @Test
    void aDepartmentScopedActorCannotNameAManagerOutsideTheirScope() {
        UUID tenant = fixture.createTenant("rl-narrow-out");
        UUID[] w = narrowWorld(tenant);
        assertThatThrownBy(() -> fixture.runAsUser(tenant, w[1], () ->
                users.update(w[3], new UserAdminService.UpdateUserRequest("R", w[0], w[4]))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aDepartmentScopedActorCanSetAnInScopeManager() {
        UUID tenant = fixture.createTenant("rl-narrow-in");
        UUID[] w = narrowWorld(tenant);
        fixture.runAsUser(tenant, w[1], () ->
                assertThat(users.update(w[3], new UserAdminService.UpdateUserRequest("R", w[0], w[2])).managerId())
                        .isEqualTo(w[2]));
    }

    private int auditCount(String action, UUID resourceId) {
        return ownerJdbc().queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = ? AND resource_id = ?",
                Integer.class, action, resourceId);
    }

    @Test
    void managerAndHeadChangesAreAuditedOnlyWhenTheyChange() {
        UUID tenant = fixture.createTenant("rl-audit");
        var admin = fixture.createAdminUser(tenant, "admin@audit.test");
        UUID boss = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "boss@audit.test"));
        UUID report = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "r@audit.test"));
        UUID[] dept = new UUID[1];
        fixture.runAsUser(tenant, admin.getId(), () -> {
            users.update(report, new UserAdminService.UpdateUserRequest("R", null, boss));
            users.update(report, new UserAdminService.UpdateUserRequest("R2", null, boss)); // unchanged
            dept[0] = org.createDepartment(new OrgStructureService.DepartmentRequest("D", "d")).id();
            org.updateDepartment(dept[0], new OrgStructureService.DepartmentRequest("D", "d", boss));
            org.updateDepartment(dept[0], new OrgStructureService.DepartmentRequest("D2", "d", boss)); // unchanged
        });
        assertThat(auditCount("user.manager_changed", report)).isEqualTo(1);
        assertThat(auditCount("department.head_changed", dept[0])).isEqualTo(1);
        // Clearing writes a null in the payload map; it must record, not throw.
        fixture.runAsUser(tenant, admin.getId(), () -> {
            users.update(report, new UserAdminService.UpdateUserRequest("R", null, null));
            org.updateDepartment(dept[0], new OrgStructureService.DepartmentRequest("D", "d", null));
        });
        assertThat(auditCount("user.manager_changed", report)).isEqualTo(2);
        assertThat(auditCount("department.head_changed", dept[0])).isEqualTo(2);
    }

    @Test
    void theUpdateRequestAndViewStayFieldForFieldAligned() {
        var request = java.util.Arrays.stream(OrgStructureService.DepartmentRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        var view = java.util.Arrays.stream(OrgStructureService.DepartmentView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(view).containsAll(request);
        var userRequest = java.util.Arrays.stream(UserAdminService.UpdateUserRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        var userView = java.util.Arrays.stream(UserAdminService.UserView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertThat(userView).containsAll(userRequest);
    }
}
