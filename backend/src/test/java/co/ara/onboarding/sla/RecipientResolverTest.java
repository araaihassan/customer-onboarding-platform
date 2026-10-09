package co.ara.onboarding.sla;

import co.ara.onboarding.identity.ReportingLineDirectory;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 6.2: manager, then department head, then administrators; never the late person unless they are the
 * tenant's sole active administrator, and never silently nobody.
 */
class RecipientResolverTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired RecipientResolver resolver;

    private RecipientResolver.Resolution resolve(UUID tenant, UUID late) {
        return fixture.runAsReturning(tenant, () -> resolver.resolve(late));
    }

    private void makeAdmin(UUID tenant, String email) {
        fixture.createAdminUser(tenant, email);
        ownerJdbc().update("UPDATE role SET name = 'Administrator' WHERE id = ?", fixture.administratorRoleId(tenant));
    }

    @Test
    void aSoleAdministratorWhoIsLateIsTheirOwnRecipient() {
        // 6B spec 5.4: the only administrator owns the late milestone and has no manager or head.
        UUID t = fixture.createTenant("rr-sole");
        fixture.runAs(t, () -> { });   // materialise the fixture's own plumbing administrator first
        UUID admin = fixture.createAdminUser(t, "only@rr-sole.test").getId();
        ownerJdbc().update("update role set name = 'Administrator' where id = ?", fixture.administratorRoleId(t));
        ownerJdbc().update("update app_user set status = 'DEACTIVATED' where id <> ? and id in "
                + "(select user_id from user_role where role_id = ?)", admin, fixture.administratorRoleId(t));
        var resolution = resolve(t, admin);
        assertThat(resolution.route()).isEqualTo(EscalationRoute.ADMINISTRATORS);
        assertThat(resolution.recipients()).extracting(ReportingLineDirectory.Recipient::userId)
                .containsExactly(admin);
    }

    @Test
    void aManagerIsFirst() {
        UUID t = fixture.createTenant("rr-mgr");
        UUID[] ids = fixture.runAsReturning(t, () -> new UUID[] {
                fixture.createUser(t, "boss@rr-mgr.test"), fixture.createUser(t, "worker@rr-mgr.test")});
        ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", ids[0], ids[1]);
        var r = resolve(t, ids[1]);
        assertThat(r.route()).isEqualTo(EscalationRoute.MANAGER);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::userId).containsExactly(ids[0]);
    }

    @Test
    void noManagerFallsToTheDepartmentHead() {
        UUID t = fixture.createTenant("rr-head");
        UUID[] ids = fixture.runAsReturning(t, () -> {
            UUID dept = fixture.createDepartment(t, "Ops");
            return new UUID[] {dept, fixture.createUserInDepartment(t, "head@rr-head.test", dept),
                    fixture.createUserInDepartment(t, "worker@rr-head.test", dept)};
        });
        ownerJdbc().update("UPDATE department SET head_user_id = ? WHERE id = ?", ids[1], ids[0]);
        var r = resolve(t, ids[2]);
        assertThat(r.route()).isEqualTo(EscalationRoute.DEPARTMENT_HEAD);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::userId).containsExactly(ids[1]);
    }

    @Test
    void fallsThroughInactiveAndSelfToAdministrators() {
        UUID t = fixture.createTenant("rr-fall");
        makeAdmin(t, "admin@rr-fall.test");
        UUID[] ids = fixture.runAsReturning(t, () -> {
            UUID dept = fixture.createDepartment(t, "Ops");
            return new UUID[] {dept, fixture.createUser(t, "boss@rr-fall.test"),
                    fixture.createUserInDepartment(t, "late@rr-fall.test", dept)};
        });
        ownerJdbc().update("UPDATE app_user SET manager_id = ? WHERE id = ?", ids[1], ids[2]);
        ownerJdbc().update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id = ?", ids[1]);
        ownerJdbc().update("UPDATE department SET head_user_id = ? WHERE id = ?", ids[2], ids[0]);
        var r = resolve(t, ids[2]);
        assertThat(r.route()).isEqualTo(EscalationRoute.ADMINISTRATORS);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::email).contains("admin@rr-fall.test");
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::userId)
                .doesNotContain(ids[2], ids[1]);
    }

    @Test
    void aNullLatePersonGoesStraightToAdministrators() {
        UUID t = fixture.createTenant("rr-null");
        makeAdmin(t, "admin@rr-null.test");
        var r = resolve(t, null);
        assertThat(r.route()).isEqualTo(EscalationRoute.ADMINISTRATORS);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::email).contains("admin@rr-null.test");
    }

    @Test
    void noActiveAdministratorYieldsAnEmptyAdministratorsRoute() {
        UUID t = fixture.createTenant("rr-none");
        fixture.runAs(t, () -> { });   // materialise the fixture's own plumbing administrator before deactivating
        var gone = fixture.createAdminUser(t, "gone@rr-none.test");
        ownerJdbc().update("UPDATE role SET name = 'Administrator' WHERE id = ?", fixture.administratorRoleId(t));
        ownerJdbc().update("UPDATE app_user SET status = 'DEACTIVATED' WHERE id IN "
                + "(SELECT user_id FROM user_role WHERE role_id = ?)", fixture.administratorRoleId(t));
        var r = resolve(t, null);
        assertThat(r.route()).isEqualTo(EscalationRoute.ADMINISTRATORS);
        assertThat(r.recipients()).isEmpty();
        assertThat(r.primaryUserId()).isNull();
        assertThat(gone.getId()).isNotNull();
    }

    @Test
    void anActiveAdministratorIsResolvedAsTheControl() {
        UUID t = fixture.createTenant("rr-live");
        makeAdmin(t, "live@rr-live.test");
        assertThat(resolve(t, null).recipients()).extracting(ReportingLineDirectory.Recipient::email)
                .contains("live@rr-live.test");
    }

    @Test
    void aLatePersonWhoIsAnAdministratorIsDroppedFromTheAdministratorsRoute() {
        UUID t = fixture.createTenant("rr-selfadm");
        UUID late = fixture.createAdminUser(t, "late@rr-selfadm.test").getId();
        makeAdmin(t, "other@rr-selfadm.test");
        var r = resolve(t, late);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::userId).doesNotContain(late);
        assertThat(r.recipients()).extracting(ReportingLineDirectory.Recipient::email).contains("other@rr-selfadm.test");
    }
}
