package co.ara.onboarding.identity;

import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentityPersistenceTest extends PostgresTestBase {

    @Autowired AppUserRepository users;
    @Autowired DepartmentRepository departments;
    @Autowired TeamRepository teams;
    @Autowired TenantFixture fixture;

    @Test
    void emailIsUniqueWithinTenantButNotAcrossTenants() {
        UUID tenantA = fixture.createTenant("alpha");
        UUID tenantB = fixture.createTenant("beta");

        fixture.runAs(tenantA, () -> users.save(newUser(tenantA, "shared@example.com")));
        fixture.runAs(tenantB, () -> users.save(newUser(tenantB, "shared@example.com")));

        // The exception must propagate OUT of runAs, not be caught inside it: once
        // saveAndFlush throws, Spring's transactional advice on the repository call
        // has already marked the enclosing TransactionTemplate transaction
        // rollback-only. Catching the exception inside the runnable lets that
        // runnable return normally, so runAs's TransactionTemplate then attempts to
        // COMMIT a rollback-only transaction and throws UnexpectedRollbackException
        // instead -- masking the DataIntegrityViolationException this test means to
        // assert. Asserting on the runAs call itself avoids that: the exception
        // propagates before any commit is attempted.
        assertThatThrownBy(() ->
            fixture.runAs(tenantA, () -> users.saveAndFlush(newUser(tenantA, "shared@example.com"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsUserByEmailWithinTenant() {
        UUID tenant = fixture.createTenant("gamma");
        fixture.runAs(tenant, () -> {
            users.save(newUser(tenant, "person@example.com"));
            assertThat(users.findByTenantIdAndEmailIgnoreCase(tenant, "PERSON@example.com"))
                    .isPresent();
        });
    }

    @Test
    void departmentNameIsUniqueWithinTenant() {
        UUID tenant = fixture.createTenant("epsilon");
        fixture.runAs(tenant, () -> departments.save(newDepartment(tenant, "Sales")));

        // See emailIsUniqueWithinTenantButNotAcrossTenants above for why the
        // assertion must wrap the runAs call itself rather than be nested inside
        // it: nesting it would let the runnable return normally after catching
        // the exception, and TransactionTemplate would then attempt to commit a
        // transaction Spring's own advice already marked rollback-only,
        // throwing UnexpectedRollbackException instead of the exception this
        // test means to assert.
        assertThatThrownBy(() ->
            fixture.runAs(tenant, () -> departments.saveAndFlush(newDepartment(tenant, "Sales"))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void teamMembershipPersistsWithCorrectTenantId() {
        UUID tenant = fixture.createTenant("delta");
        AtomicReference<UUID> userId = new AtomicReference<>();
        AtomicReference<UUID> teamId = new AtomicReference<>();

        fixture.runAs(tenant, () -> {
            Department dept = newDepartment(tenant, "Engineering");
            departments.save(dept);

            Team team = newTeam(tenant, "Platform", dept.getId());
            teams.save(team);
            teamId.set(team.getId());

            AppUser user = newUser(tenant, "member@example.com");
            user.getTeamIds().add(team.getId());
            users.save(user);
            userId.set(user.getId());
        });

        // Fresh transaction (a separate runAs call, hence a separate Hibernate
        // session) so this reload cannot be answered from the persistence
        // context that did the writing -- it has to come back from the database.
        fixture.runAs(tenant, () -> {
            AppUser reloaded = users.findById(userId.get()).orElseThrow();
            assertThat(reloaded.getTeamIds())
                    .as("team membership must survive a reload in a fresh session")
                    .containsExactly(teamId.get());
        });

        // Read the raw row through the owner connection to confirm team_member's
        // tenant_id column itself -- not just what comes back through the
        // Hibernate collection mapping -- was actually populated correctly. This
        // is a plain column-value check, not an RLS/privilege assertion, so
        // going through the owner role here does not undermine it the way it
        // would for an isolation test.
        Long matchingRows = ownerJdbc().queryForObject(
                "SELECT count(*) FROM team_member WHERE user_id = ? AND team_id = ? AND tenant_id = ?",
                Long.class, userId.get(), teamId.get(), tenant);
        assertThat(matchingRows)
                .as("team_member.tenant_id must be populated from the owning user's tenant")
                .isEqualTo(1L);
    }

    private AppUser newUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(Uuid7.generate());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setPasswordHash("x");
        u.setFullName("Test Person");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return u;
    }

    private Department newDepartment(UUID tenantId, String name) {
        Department d = new Department();
        d.setId(Uuid7.generate());
        d.setTenantId(tenantId);
        d.setName(name);
        return d;
    }

    private Team newTeam(UUID tenantId, String name, UUID departmentId) {
        Team t = new Team();
        t.setId(Uuid7.generate());
        t.setTenantId(tenantId);
        t.setName(name);
        t.setDepartmentId(departmentId);
        return t;
    }
}
