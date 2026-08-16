package co.ara.onboarding.identity;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.auth.ActivationService;
import co.ara.onboarding.auth.RefreshTokenService;
import co.ara.onboarding.auth.RotationOutcome;
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
import java.util.NoSuchElementException;
import java.util.Set;
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
    @Autowired RefreshTokenService refreshTokens;
    @Autowired TenantFixture fixture;
    @Autowired AuditEventRepository auditEvents;

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
     * deactivate() wrote AuditActions.USER_CREATED. Only the prose summary said
     * "Deactivated"; the action KEY on the row — the field every consumer filters
     * on, including sub-project 2's Activity Timeline — read user.created. A wrong
     * audit record is worse than a missing one: it asserts something that did not
     * happen, and audit_event is append-only, so rows already written cannot be
     * corrected.
     *
     * The target is created through the fixture rather than userAdmin.create, so
     * no legitimate user.created exists for it and the absence assertion below
     * cannot be satisfied by accident.
     */
    @Test
    void deactivatingAUserIsKeyedAsADeactivationNotACreation() {
        UUID tenant = fixture.createTenant("admin-deact-audit");
        var admin = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "auditboss@example.com"));
            target.set(fixture.createUser(tenant, "auditleaver@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () -> userAdmin.deactivate(target.get()));

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> target.get().equals(e.getResourceId()))
                    .extracting(e -> e.getAction())
                    .as("the action key, not just the summary, must say what happened")
                    .containsExactly("user.deactivated"));
    }

    @Test
    void updatingAUserIsAudited() {
        UUID tenant = fixture.createTenant("admin-update-audit");
        var admin = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "auditeditor@example.com"));
            target.set(fixture.createUser(tenant, "audittarget@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () ->
            userAdmin.update(target.get(),
                    new UserAdminService.UpdateUserRequest("Renamed Person", null)));

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> target.get().equals(e.getResourceId()))
                    .extracting(e -> e.getAction())
                    .containsExactly("user.updated"));
    }

    /**
     * Identity actions stay OFF the customer-visible timeline. The flag is decided
     * by whose record it is, not by how weighty the verb is: customer.deactivated
     * is visible because the customer's own record changed, whereas a vendor's
     * internal staffing change is not the customer's business and putting it on
     * their timeline would leak the vendor's org changes.
     */
    @Test
    void identityAuditEventsAreNotTimelineVisible() {
        UUID tenant = fixture.createTenant("admin-audit-flag");
        var admin = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "flagboss@example.com"));
            target.set(fixture.createUser(tenant, "flagtarget@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
        });

        fixture.runAsUser(tenant, admin.get(), () -> {
            userAdmin.update(target.get(),
                    new UserAdminService.UpdateUserRequest("Flag Person", null));
            userAdmin.deactivate(target.get());
        });

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> target.get().equals(e.getResourceId()))
                    .allSatisfy(e -> assertThat(e.isTimelineVisible()).isFalse()));
    }

    /**
     * The half of deactivation that was missing. Setting the column stopped nothing:
     * UserStatus.ACTIVE was consulted in exactly one place in the whole main source
     * tree — LoginService — and the departing employee's browser never logs in
     * again. It holds a refresh cookie, client.ts renews silently on every 401, and
     * each rotation issued a FRESH fourteen-day token, so the account kept its full
     * role set indefinitely. Only closing the tab stopped it.
     *
     * PasswordResetService already revoked every session for exactly this reason.
     */
    @Test
    void deactivatingAUserEndsTheirRefreshSession() {
        UUID tenant = fixture.createTenant("admin-deact-session");
        var admin = new AtomicReference<UUID>();
        AppUser target = fixture.createUserWithPassword(
                tenant, "departing@example.com", "password-value");
        var raw = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "sessionboss@example.com"));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), role);
            raw.set(refreshTokens.issue(target, "127.0.0.1", "test-agent"));
        });

        // Positive control: the session is live right up until the deactivation, so
        // the rejection below is the deactivation and not a broken token.
        var live = new AtomicReference<RotationOutcome>();
        fixture.runAs(tenant, () -> live.set(refreshTokens.rotate(raw.get())));
        assertThat(live.get()).isInstanceOf(RotationOutcome.Rotated.class);
        String renewed = ((RotationOutcome.Rotated) live.get()).newRawToken();

        fixture.runAsUser(tenant, admin.get(), () -> userAdmin.deactivate(target.getId()));

        var afterwards = new AtomicReference<RotationOutcome>();
        fixture.runAs(tenant, () -> afterwards.set(refreshTokens.rotate(renewed)));
        assertThat(afterwards.get())
                .as("deactivation must end the session, not just set a column")
                .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.REVOKED));
    }

    /**
     * The remaining fifteen minutes of an already-issued access token. Revoking the
     * refresh family stops renewal but says nothing about the token the browser is
     * already holding, and nothing on the authenticated request path consulted
     * status. Authority is resolved server-side per request precisely so a
     * revocation takes effect on the next call rather than when a token happens to
     * expire — a deactivated account must resolve to no authority at all.
     */
    @Test
    void aDeactivatedUsersLiveAccessTokenGrantsNoAuthority() {
        UUID tenant = fixture.createTenant("admin-deact-authority");
        var admin = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            admin.set(fixture.createUser(tenant, "authboss@example.com"));
            target.set(fixture.createUser(tenant, "authleaver@example.com"));
            UUID adminRole = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(admin.get(), adminRole);
            UUID viewerRole = roles.createRole("Viewer", "",
                    Map.of(PermissionKeys.USER_VIEW, Scope.ALL));
            roles.assignRole(target.get(), viewerRole);
        });

        // Positive control: the grant works while the account is ACTIVE, so the
        // denial below is the deactivation and not a missing role.
        var before = new AtomicReference<Long>();
        fixture.runAsUser(tenant, target.get(), () ->
                before.set(userAdmin.list(null, Pageable.unpaged()).getTotalElements()));
        assertThat(before.get()).isPositive();

        fixture.runAsUser(tenant, admin.get(), () -> userAdmin.deactivate(target.get()));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, target.get(),
                () -> userAdmin.list(null, Pageable.unpaged())))
                .as("a deactivated account must hold no authority on the next request")
                .isInstanceOf(AccessDeniedException.class);
    }

    // ---------------------------------------------------------------------------
    // user.manage at a NARROW scope.
    //
    // Every write case above grants USER_MANAGE at Scope.ALL, which is why nothing
    // caught this: only USER_VIEW was ever exercised at DEPARTMENT. The catalog
    // offers user.manage at ALL, DEPARTMENT and TEAM, and RoleEditor reads those
    // options straight from /admin/permissions -- so the narrow scopes are offered
    // to tenants as a meaningful restriction and must be one.
    //
    // The failure they close: @RequirePermission cannot see arguments, so a gate
    // that passes says only "this actor may manage SOME users". Whether they may
    // manage THIS user is a record-level question, and the only thing that answers
    // it is resolving the id through AuthorizedQuery before writing.
    //
    // Each of these asserts NoSuchElementException and explicitly not
    // AccessDeniedException: an out-of-scope record is a 404, never a 403, or the
    // response becomes a probe for which users exist elsewhere in the tenant.
    // ---------------------------------------------------------------------------

    /** deptA, deptB, an Ops Lead scoped to deptA, and a wider role to steal. */
    private record NarrowScopeWorld(UUID deptA, UUID deptB, UUID admin,
                                    UUID peer, UUID outsider, UUID wideRole) {}

    private NarrowScopeWorld narrowScopeWorld(UUID tenant) {
        var world = new AtomicReference<NarrowScopeWorld>();
        fixture.runAs(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "Dept A");
            UUID deptB = fixture.createDepartment(tenant, "Dept B");
            UUID admin = fixture.createUserInDepartment(tenant, "opslead@example.com", deptA);
            UUID peer = fixture.createUserInDepartment(tenant, "teammate@example.com", deptA);
            UUID outsider = fixture.createUserInDepartment(tenant, "elsewhere@example.com", deptB);

            // The obvious way to delegate people-management to a department head,
            // and exactly what the permission catalog advertises.
            UUID opsLead = roles.createRole("Ops Lead", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.DEPARTMENT,
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            roles.assignRole(admin, opsLead);

            UUID wide = roles.createRole("Tenant Wide", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            world.set(new NarrowScopeWorld(deptA, deptB, admin, peer, outsider, wide));
        });
        return world.get();
    }

    private Set<UUID> rolesHeldBy(UUID tenant, UUID userId) {
        var held = new AtomicReference<Set<UUID>>();
        fixture.runAs(tenant, () -> held.set(userAdmin.get(userId).roleIds()));
        return held.get();
    }

    @Test
    void departmentScopedAdminCannotAssignARoleToAUserOutsideTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-assign-out");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, w.admin(),
                () -> userAdmin.assignRole(w.outsider(), w.wideRole())))
                .as("a department head must not reach users in another department")
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);

        assertThat(rolesHeldBy(tenant, w.outsider()))
                .as("and nothing may have been written before the refusal")
                .isEmpty();
    }

    /** Positive control: the delegation the catalog advertises still works. */
    @Test
    void departmentScopedAdminCanAssignARoleWithinTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-assign-in");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        fixture.runAsUser(tenant, w.admin(), () -> userAdmin.assignRole(w.peer(), w.wideRole()));

        assertThat(rolesHeldBy(tenant, w.peer())).containsExactly(w.wideRole());
    }

    @Test
    void departmentScopedAdminCannotUnassignARoleFromAUserOutsideTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-unassign-out");
        NarrowScopeWorld w = narrowScopeWorld(tenant);
        fixture.runAs(tenant, () -> roles.assignRole(w.outsider(), w.wideRole()));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, w.admin(),
                () -> userAdmin.unassignRole(w.outsider(), w.wideRole())))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);

        assertThat(rolesHeldBy(tenant, w.outsider()))
                .as("the grant must survive the refusal")
                .containsExactly(w.wideRole());
    }

    /** Positive control for the removal half. */
    @Test
    void departmentScopedAdminCanUnassignARoleWithinTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-unassign-in");
        NarrowScopeWorld w = narrowScopeWorld(tenant);
        fixture.runAs(tenant, () -> roles.assignRole(w.peer(), w.wideRole()));

        fixture.runAsUser(tenant, w.admin(), () -> userAdmin.unassignRole(w.peer(), w.wideRole()));

        assertThat(rolesHeldBy(tenant, w.peer())).isEmpty();
    }

    /**
     * create() wrote request.departmentId() unchecked, so the same actor could
     * populate any department in the tenant — and then manage the user they had
     * just put out of their own reach.
     */
    @Test
    void departmentScopedAdminCannotCreateAUserIntoAnotherDepartment() {
        UUID tenant = fixture.createTenant("narrow-create-out");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, w.admin(),
                () -> userAdmin.create(new UserAdminService.CreateUserRequest(
                        "planted@example.com", "Planted", w.deptB()))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);

        var found = new AtomicReference<Boolean>();
        fixture.runAs(tenant, () -> found.set(users
                .findByTenantIdAndEmailIgnoreCase(tenant, "planted@example.com").isPresent()));
        assertThat(found.get()).as("the create must roll back, not half-apply").isFalse();
    }

    /** Positive control: creating into one's own department is the point of the role. */
    @Test
    void departmentScopedAdminCanCreateAUserIntoTheirOwnDepartment() {
        UUID tenant = fixture.createTenant("narrow-create-in");
        NarrowScopeWorld w = narrowScopeWorld(tenant);
        var created = new AtomicReference<UserAdminService.UserView>();

        fixture.runAsUser(tenant, w.admin(), () -> created.set(userAdmin.create(
                new UserAdminService.CreateUserRequest(
                        "recruit@example.com", "A Recruit", w.deptA()))));

        assertThat(created.get().departmentId()).isEqualTo(w.deptA());
        assertThat(created.get().status()).isEqualTo(UserStatus.INVITED);
    }

    /**
     * The same unchecked departmentId on the update path: a user in scope can be
     * pushed out of it, which is a write to a record the actor could not have read
     * a moment later.
     */
    @Test
    void departmentScopedAdminCannotMoveAUserIntoAnotherDepartment() {
        UUID tenant = fixture.createTenant("narrow-update-out");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, w.admin(),
                () -> userAdmin.update(w.peer(),
                        new UserAdminService.UpdateUserRequest("Moved Person", w.deptB()))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);

        var after = new AtomicReference<UserAdminService.UserView>();
        fixture.runAs(tenant, () -> after.set(userAdmin.get(w.peer())));
        assertThat(after.get().departmentId())
                .as("the move must roll back entirely")
                .isEqualTo(w.deptA());
        assertThat(after.get().fullName()).isNotEqualTo("Moved Person");
    }

    /** Positive control: an in-department rename still works. */
    @Test
    void departmentScopedAdminCanUpdateAUserWithinTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-update-in");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        fixture.runAsUser(tenant, w.admin(), () -> userAdmin.update(w.peer(),
                new UserAdminService.UpdateUserRequest("Renamed Teammate", w.deptA())));

        var after = new AtomicReference<UserAdminService.UserView>();
        fixture.runAs(tenant, () -> after.set(userAdmin.get(w.peer())));
        assertThat(after.get().fullName()).isEqualTo("Renamed Teammate");
    }

    /**
     * The third instance of the same shape, and the one nothing could have flagged:
     * auth sits outside the two packages
     * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly covers,
     * so UserInvitationService reading its target with users.findById under a
     * user.manage gate was invisible to the guard. A DEPARTMENT-scoped holder could
     * mint and MAIL an activation invitation for any user in the tenant.
     */
    @Test
    void departmentScopedAdminCannotInviteAUserOutsideTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-invite-out");
        NarrowScopeWorld w = narrowScopeWorld(tenant);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, w.admin(),
                () -> activations.issueForUser(w.outsider())))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /** Positive control: inviting inside the department still issues a usable token. */
    @Test
    void departmentScopedAdminCanInviteAUserWithinTheirDepartment() {
        UUID tenant = fixture.createTenant("narrow-invite-in");
        NarrowScopeWorld w = narrowScopeWorld(tenant);
        var token = new AtomicReference<String>();

        fixture.runAsUser(tenant, w.admin(), () -> token.set(activations.issueForUser(w.peer())));

        assertThat(token.get()).isNotBlank();
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
