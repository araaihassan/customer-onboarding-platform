package co.ara.onboarding.identity;

import co.ara.onboarding.auth.ActivationService;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserAdminTest extends PostgresTestBase {

    @Autowired UserAdminService userAdmin;
    @Autowired RoleService roles;
    @Autowired AppUserRepository users;
    @Autowired UserActivationSender activations;
    @Autowired ActivationService activation;
    @Autowired TenantFixture fixture;

    @Test
    void listingUsersRequiresUserViewPermission() {
        UUID tenant = fixture.createTenant("admin-denied");
        var user = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> user.set(fixture.createUser(tenant, "plain@example.com")));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> userAdmin.list(null, Pageable.unpaged())))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * The reason user.view is scoped rather than ALL-only. A department admin sees
     * their own department and nothing else, resolved by AppUserDescriptor rather
     * than by anything UserAdminService does itself.
     */
    @Test
    void departmentScopedAdminSeesOnlyOwnDepartmentUsers() {
        UUID tenant = fixture.createTenant("admin-dept-scope");
        var admin = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "Dept A");
            UUID deptB = fixture.createDepartment(tenant, "Dept B");
            admin.set(fixture.createUserInDepartment(tenant, "admin@example.com", deptA));
            fixture.createUserInDepartment(tenant, "peer@example.com", deptA);
            fixture.createUserInDepartment(tenant, "outsider@example.com", deptB);

            UUID role = roles.createRole("Dept Admin", "",
                    Map.of(PermissionKeys.USER_VIEW, Scope.DEPARTMENT));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () ->
            assertThat(userAdmin.list(null, Pageable.unpaged()))
                .extracting(UserAdminService.UserView::email)
                .containsExactlyInAnyOrder("admin@example.com", "peer@example.com"));
    }

    /**
     * Not in the plan. A created user must be INVITED with no usable password —
     * creating them ACTIVE would mean an administrator could mint an account and
     * then set its password, which is an account-takeover path rather than an
     * invitation.
     */
    @Test
    void createdUserIsInvitedAndCannotLogInYet() {
        UUID tenant = fixture.createTenant("admin-create");
        var admin = new AtomicReference<UUID>();
        var created = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "creator@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () ->
            created.set(userAdmin.create(new UserAdminService.CreateUserRequest(
                    "newcomer@example.com", "New Comer", null)).id()));

        fixture.runAs(tenant, () -> {
            AppUser user = users.findById(created.get()).orElseThrow();
            assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);
            assertThat(user.getUserType()).isEqualTo(co.ara.onboarding.platform.UserType.INTERNAL);
            assertThat(user.getPasswordHash())
                    .as("no password until the invitation is accepted")
                    .isNull();
        });
    }

    /**
     * Not in the plan, and the flow Task 25's manual verification depends on. An
     * administrator creates a colleague; the colleague activates and only then has a
     * password. Exercises ActivationService's second branch — the same token table,
     * expiry and single-use rules as a portal invitation, pointed at an existing
     * user rather than at a contact.
     */
    @Test
    void aCreatedUserCanActivateAndBecomesActive() {
        UUID tenant = fixture.createTenant("admin-activate");
        var admin = new AtomicReference<UUID>();
        var created = new AtomicReference<UUID>();
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "inviter@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () -> {
            created.set(userAdmin.create(new UserAdminService.CreateUserRequest(
                    "colleague@example.com", "A Colleague", null)).id());
            token.set(activations.issueForUser(created.get()));
        });

        fixture.runAs(tenant, () -> {
            var activated = activation.accept(token.get(), "a-sufficiently-long-password");
            assertThat(activated.getId()).isEqualTo(created.get());
            assertThat(activated.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(activated.getPasswordHash()).isNotNull();
        });
    }

    /**
     * Not in the plan. Activation must not resurrect a deactivated account or silently
     * act as a password reset without the reset flow's one-hour window — only an
     * INVITED account is activatable.
     */
    @Test
    void activationIsRefusedForAnAccountThatIsNotInvited() {
        UUID tenant = fixture.createTenant("admin-activate-twice");
        var admin = new AtomicReference<UUID>();
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "admin2@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        // An already-ACTIVE user, then a token issued against them anyway.
        var existing = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> existing.set(fixture.createUser(tenant, "already@example.com")));
        fixture.runAsUser(tenant, admin.get(), () -> token.set(activations.issueForUser(existing.get())));

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> activation.accept(token.get(), "a-sufficiently-long-password")))
                .isInstanceOf(co.ara.onboarding.auth.InvalidTokenException.class);
    }

    @Test
    void creatingAUserRequiresUserManageNotJustUserView() {
        UUID tenant = fixture.createTenant("admin-create-denied");
        var viewer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            viewer.set(fixture.createUser(tenant, "viewonly@example.com"));
            UUID role = roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.USER_VIEW, Scope.ALL));
            roles.assignRole(viewer.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, viewer.get(),
                () -> userAdmin.create(new UserAdminService.CreateUserRequest(
                        "nope@example.com", "Nope", null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Not in the plan. Deactivation is how a user is removed — there is no delete —
     * so it must actually stop them logging in, and it must be gated on USER_MANAGE.
     */
    @Test
    void deactivatingAUserSetsDeactivatedStatus() {
        UUID tenant = fixture.createTenant("admin-deactivate");
        var admin = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "boss@example.com"));
            target.set(fixture.createUser(tenant, "leaver@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () -> userAdmin.deactivate(target.get()));

        fixture.runAs(tenant, () ->
            assertThat(users.findById(target.get()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.DEACTIVATED));
    }

    /**
     * Task 28 defect. UserView carried no roles, so the administration screen could
     * assign one and then show nothing different — and DELETE /users/{id}/roles/{roleId}
     * was unreachable from any interface, because nothing could name a role the user
     * already held. Which roles someone holds is part of viewing that user, so it is
     * on the view rather than behind a second request.
     *
     * roleIds is read-only: UpdateUserRequest does not accept it, so the
     * view-carries-every-request-field invariant is untouched — the view is a
     * superset, which is the safe direction.
     */
    @Test
    void userViewCarriesTheRolesTheUserHolds() {
        UUID tenant = fixture.createTenant("admin-user-roles");
        var admin = new AtomicReference<UUID>();
        var role = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "rolereader@example.com"));
            role.set(roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL)));
            roles.assignRole(admin.get(), role.get());
        });

        fixture.runAsUser(tenant, admin.get(), () ->
            assertThat(userAdmin.get(admin.get()).roleIds()).containsExactly(role.get()));
    }
}
