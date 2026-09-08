package co.ara.onboarding.identity;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.UserRoleDirectory;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAdminService {

    public record CreateUserRequest(String email, String fullName, UUID departmentId) {}
    public record UpdateUserRequest(String fullName, UUID departmentId) {}
    /**
     * roleIds is read-only and deliberately absent from UpdateUserRequest: role
     * assignment goes through the dedicated POST/DELETE endpoints, which are gated
     * and audited, rather than riding along on a full-replace PUT where an omitted
     * field would silently strip every role the user holds.
     */
    public record UserView(UUID id, String email, String fullName, UserType userType,
                           UserStatus status, UUID departmentId, Set<UUID> teamIds,
                           Set<UUID> roleIds) {}

    private final AppUserRepository repository;
    private final AuthorizedQuery authorizedQuery;
    private final UserActivationSender activations;
    private final UserSessionRevoker sessions;
    private final RoleService roles;
    private final UserRoleDirectory assignments;
    private final AuditRecorder audit;

    public UserAdminService(AppUserRepository repository, AuthorizedQuery authorizedQuery,
                            UserActivationSender activations, UserSessionRevoker sessions,
                            RoleService roles, UserRoleDirectory assignments,
                            AuditRecorder audit) {
        this.repository = repository;
        this.authorizedQuery = authorizedQuery;
        this.activations = activations;
        this.sessions = sessions;
        this.roles = roles;
        this.assignments = assignments;
        this.audit = audit;
    }

    /**
     * Scoped through AuthorizedQuery, so a DEPARTMENT-scoped administrator sees only
     * their own department. That resolution is AppUserDescriptor's job, not this
     * method's — which is why there is no departmentId filter here.
     */
    @RequirePermission(PermissionKeys.USER_VIEW)
    @Transactional(readOnly = true)
    public Page<UserView> list(String search, Pageable pageable) {
        Specification<AppUser> filters = (root, query, cb) -> {
            if (search == null || search.isBlank()) return cb.conjunction();
            String pattern = "%" + search.toLowerCase() + "%";
            return cb.or(cb.like(cb.lower(root.get("email")), pattern),
                         cb.like(cb.lower(root.get("fullName")), pattern));
        };
        Page<AppUser> page = authorizedQuery.findAll(repository, AppUser.class,
                PermissionKeys.USER_VIEW, filters, pageable);
        // One assignment lookup for the whole page, not one per row.
        Map<UUID, Set<UUID>> byUser = assignments.roleIdsByUser(
                page.getContent().stream().map(AppUser::getId).toList());
        return page.map(user -> toView(user, byUser.getOrDefault(user.getId(), Set.of())));
    }

    @RequirePermission(PermissionKeys.USER_VIEW)
    @Transactional(readOnly = true)
    public UserView get(UUID id) {
        return toView(authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_VIEW, id));
    }

    /**
     * Creates an INTERNAL user in INVITED status with **no password hash**, then
     * issues an activation invitation. The account cannot be signed into until the
     * invitation is accepted — an administrator can create colleagues but never mint
     * a usable credential for one, which would be an account-takeover path rather
     * than an invitation.
     *
     * departmentId arrives from the request body and is therefore invisible to
     * @RequirePermission, which sees no arguments: the gate can only say the actor
     * may manage SOME users. requireWithinManageScope below is what says they may
     * manage THIS one — without it a DEPARTMENT-scoped holder could plant a user in
     * any department in the tenant.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public UserView create(CreateUserRequest request) {
        AppUser user = new AppUser();
        user.setId(Uuid7.generate());
        user.setTenantId(TenantContext.getRequired());
        user.setEmail(request.email());
        user.setFullName(request.fullName());
        user.setUserType(UserType.INTERNAL);
        user.setStatus(UserStatus.INVITED);
        user.setDepartmentId(request.departmentId());
        repository.saveAndFlush(user);
        requireWithinManageScope(user.getId());

        activations.issueForUser(user.getId());

        audit.record(AuditActions.USER_CREATED, "app_user", user.getId(),
                "Created user " + user.getEmail(), Map.of());
        return toView(user);
    }

    /**
     * Fetched with the write permission, never USER_VIEW — see CustomerService.update.
     *
     * Checked on the way in AND on the way out. The inbound getById proves the actor
     * may touch this user; the outbound requireWithinManageScope proves the user
     * they are left with is still one they may touch, because departmentId comes
     * from the body and moving someone into a department the actor cannot see is a
     * write whose result they could not have read.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public UserView update(UUID id, UpdateUserRequest request) {
        AppUser user = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, id);
        user.setFullName(request.fullName());
        user.setDepartmentId(request.departmentId());
        AppUser saved = repository.saveAndFlush(user);
        requireWithinManageScope(saved.getId());

        audit.record(AuditActions.USER_UPDATED, "app_user", saved.getId(),
                "Updated user " + saved.getEmail(), Map.of());
        return toView(saved);
    }

    /**
     * Deactivation is how a user is removed; there is no delete (spec 9.4). It must
     * therefore actually end the account, which means ending its sessions too:
     * setting the column alone stopped nothing, because the only place UserStatus
     * was consulted on the way in was LoginService, and a browser holding a refresh
     * cookie never logs in again. Each rotation issued a fresh full-TTL token, so
     * the account read and wrote customer records for as long as the tab stayed
     * open. PasswordResetService already revoked sessions for exactly this reason.
     *
     * Roles are deliberately left in place: a deactivated account resolves no
     * authority (AuthorizationService joins on status), and stripping the grants
     * would destroy the record of what the departing user could do — which is the
     * first thing an investigation asks. Reactivation is a sub-project 2 concern.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void deactivate(UUID id) {
        AppUser user = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, id);
        user.setStatus(UserStatus.DEACTIVATED);
        repository.save(user);
        sessions.revokeAllForUser(user.getId());
        // Ending the session is only half of "deactivation ends the account": an
        // activation or password-reset token issued before deactivation, or a fresh
        // password-reset request issued after it, would otherwise still be
        // redeemable. One call, keyed only on userId, closes both -- one Invitation
        // table covers both purposes.
        activations.revokePendingInvitations(user.getId());
        // USER_DEACTIVATED, not USER_CREATED. This recorded a creation for years'
        // worth of deactivations with only the prose summary dissenting, and the
        // action key is the field consumers filter on. audit_event is append-only,
        // so rows already written stay wrong — this fixes the ones from here on.
        audit.record(AuditActions.USER_DEACTIVATED, "app_user", user.getId(),
                "Deactivated user " + user.getEmail(), Map.of());
    }

    /**
     * Delegates to the gated RoleService rather than writing user_role here, so role
     * assignment has one implementation and one audit event wherever it is triggered.
     *
     * The target is resolved through AuthorizedQuery FIRST, and that is the whole
     * security of this method. RoleService.assignRole resolves its own target
     * through ActorDirectory, which is users.findById — tenant-bounded by RLS and
     * nothing else. So both user.manage gates could pass while the userId named a
     * user in a department the actor cannot see: "Ops Lead" = {user.view:
     * DEPARTMENT, user.manage: DEPARTMENT} could assign any role in the tenant to
     * anyone in the tenant, at any scope. @RequirePermission cannot close that,
     * because it never sees the argument.
     *
     * The escalation this method could not close on its own -- the actor assigning
     * a role wider than their own, to a user they legitimately manage, including
     * themselves -- is refused one layer down: RoleService.assignRole's
     * refuseEscalation compares every grant on the target role against the
     * caller's own effective permissions and rejects the whole role rather than
     * assigning it partially. That was the known limitation recorded here, in
     * CLAUDE.md and in the sub-project 1 plan; sub-project 2's Task 4 is the
     * tenant-administration work that closed it.
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        AppUser target = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, userId);
        roles.assignRole(target.getId(), roleId);
    }

    /** Same resolution as assignRole, for the same reason — removal is a write too. */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void unassignRole(UUID userId, UUID roleId) {
        AppUser target = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, userId);
        roles.unassignRole(target.getId(), roleId);
    }

    /**
     * The record the actor just wrote must be one they could read back. Reusing the
     * USER_MANAGE predicate rather than comparing departmentId by hand is what keeps
     * this honest for every scope the catalog offers: DEPARTMENT resolves through
     * AppUserDescriptor exactly as a read would, and TEAM and ASSIGNED fail closed
     * without a special case. A narrow-scoped actor consequently cannot create a
     * user with no department either — they would not be able to manage them a
     * moment later, which is the same thing said from the other side.
     *
     * NoSuchElementException, so the caller sees a 404 and cannot use the response
     * to learn which departments exist elsewhere in the tenant. Inside the
     * transaction, so the refused write rolls back rather than half-applying.
     */
    private void requireWithinManageScope(UUID userId) {
        authorizedQuery.getById(repository, AppUser.class, PermissionKeys.USER_MANAGE, userId);
    }

    /** Looks the assignments up for one user; the list path passes them in instead. */
    private UserView toView(AppUser u) {
        return toView(u, assignments.roleIdsByUser(List.of(u.getId()))
                .getOrDefault(u.getId(), Set.of()));
    }

    private UserView toView(AppUser u, Set<UUID> roleIds) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getUserType(),
                u.getStatus(), u.getDepartmentId(), Set.copyOf(u.getTeamIds()), roleIds);
    }
}
