package co.ara.onboarding.identity;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAdminService {

    public record CreateUserRequest(String email, String fullName, UUID departmentId) {}
    public record UpdateUserRequest(String fullName, UUID departmentId) {}
    public record UserView(UUID id, String email, String fullName, UserType userType,
                           UserStatus status, UUID departmentId, Set<UUID> teamIds) {}

    private final AppUserRepository repository;
    private final AuthorizedQuery authorizedQuery;
    private final UserActivationSender activations;
    private final RoleService roles;
    private final AuditRecorder audit;

    public UserAdminService(AppUserRepository repository, AuthorizedQuery authorizedQuery,
                            UserActivationSender activations, RoleService roles,
                            AuditRecorder audit) {
        this.repository = repository;
        this.authorizedQuery = authorizedQuery;
        this.activations = activations;
        this.roles = roles;
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
        return authorizedQuery.findAll(repository, AppUser.class,
                PermissionKeys.USER_VIEW, filters, pageable).map(this::toView);
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
        return toView(repository.save(user));
    }

    /** Deactivation is how a user is removed; there is no delete (spec 9.4). */
    @RequirePermission(PermissionKeys.USER_MANAGE)
    @Transactional
    public void deactivate(UUID id) {
        AppUser user = authorizedQuery.getById(repository, AppUser.class,
                PermissionKeys.USER_MANAGE, id);
        user.setStatus(UserStatus.DEACTIVATED);
        repository.save(user);
        audit.record(AuditActions.USER_CREATED, "app_user", user.getId(),
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

    private UserView toView(AppUser u) {
        return new UserView(u.getId(), u.getEmail(), u.getFullName(), u.getUserType(),
                u.getStatus(), u.getDepartmentId(), Set.copyOf(u.getTeamIds()));
    }
}
