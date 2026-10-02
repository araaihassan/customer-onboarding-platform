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
                .isInstanceOf(IllegalArgumentException.class);
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
