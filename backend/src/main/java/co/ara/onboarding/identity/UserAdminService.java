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

        activations.issueForUser(user.getId());

        audit.record(AuditActions.USER_CREATED, "app_user", user.getId(),
                "Created user " + user.getEmail(), Map.of());
        return toView(user);
    }

    /** Fetched with the write permission, never USER_VIEW — see CustomerService.update. */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public UserView update(UUID id, UpdateUserRequest request) {
        AppUser user = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, id);
        user.setFullName(request.fullName());
        user.setDepartmentId(request.departmentId());
        AppUser saved = repository.save(user);

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
     */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void assignRole(UUID userId, UUID roleId) {
        roles.assignRole(userId, roleId);
    }

    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void unassignRole(UUID userId, UUID roleId) {
        roles.unassignRole(userId, roleId);
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
