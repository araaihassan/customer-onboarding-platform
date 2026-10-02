package co.ara.onboarding.identity;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class ReportingLineDirectoryTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired ReportingLineDirectory lines;

    @Test
    void resolvesAnActiveManagerAndDepartmentHead() {
        UUID tenant = fixture.createTenant("rld-basic");
        // Created in a committed transaction first: ownerJdbc is a separate connection.
        UUID[] ids = fixture.runAsReturning(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "Ops");
            UUID head = fixture.createUserInDepartment(tenant, "head@rld.test", dept);
            UUID boss = fixture.createUser(tenant, "boss@rld.test");
            UUID worker = fixture.createUserInDepartment(tenant, "worker@rld.test", dept);
            return new UUID[] {dept, head, boss, worker};
        });
        ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", ids[2], ids[3]);
        ownerJdbc().update("UPDATE department SET head_user_id = ? WHERE id = ?", ids[1], ids[0]);
        fixture.runAs(tenant, () -> {
            assertThat(lines.activeManagerOf(ids[3])).map(ReportingLineDirectory.Recipient::userId).contains(ids[2]);
            assertThat(lines.activeDepartmentHeadOf(ids[3])).map(ReportingLineDirectory.Recipient::userId).contains(ids[1]);
        });
    }

    @Test
    void inactivePeopleAreNeverReturned() {
        UUID tenant = fixture.createTenant("rld-inactive");
        UUID[] ids = fixture.runAsReturning(tenant, () -> new UUID[] {
                fixture.createUser(tenant, "boss@inactive.test"), fixture.createUser(tenant, "worker@inactive.test")});
        ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", ids[0], ids[1]);
        ownerJdbc().update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", ids[0]);
        fixture.runAs(tenant, () -> assertThat(lines.activeManagerOf(ids[1])).isEmpty());
    }

    @Test
    void administratorsAreTheActiveHoldersOfTheAdministratorTemplate() {
        UUID tenant = fixture.createTenant("rld-admins");
        var admin = fixture.createAdminUser(tenant, "admin@admins.test");
        // The fixture's full-authority role may carry a fixture name; the directory matches the template name.
        ownerJdbc().update("UPDATE role SET name = 'Administrator' WHERE id = ?", fixture.administratorRoleId(tenant));
        fixture.runAs(tenant, () ->
                assertThat(lines.activeAdministrators()).extracting(ReportingLineDirectory.Recipient::userId)
                        .contains(admin.getId()));
    }

    @Test
    void anotherTenantsPeopleAreInvisible() {
        UUID a = fixture.createTenant("rld-a");
        UUID b = fixture.createTenant("rld-b");
        UUID foreign = fixture.runAsReturning(b, () -> fixture.createUser(b, "x@b.test"));
        fixture.runAs(a, () -> assertThat(lines.activeUser(foreign)).isEmpty());
    }
}
