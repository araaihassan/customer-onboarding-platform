package co.ara.onboarding.provisioning;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.PermissionCatalog;
import co.ara.onboarding.authz.InvalidGrantException;
import co.ara.onboarding.authz.Role;
import co.ara.onboarding.authz.RoleGrant;
import co.ara.onboarding.authz.RoleRepository;
import co.ara.onboarding.authz.RoleTemplates;
import co.ara.onboarding.authz.UserRole;
import co.ara.onboarding.authz.UserRoleRepository;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.identity.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.Tenant;
import co.ara.onboarding.tenancy.TenantConnectionCustomizer;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.tenancy.TenantRepository;
import co.ara.onboarding.tenancy.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Creates a tenant and seeds everything it needs to be usable.
 *
 * WHY THIS LIVES IN ITS OWN PACKAGE. Provisioning necessarily reaches into
 * tenancy, authz and identity at once. Those modules already depend on tenancy
 * (Role and AppUser both extend TenantScopedEntity), so putting this class in
 * tenancy would close two cycles -- tenancy -> authz -> tenancy and
 * tenancy -> identity -> tenancy -- which
 * ModuleBoundaryTest.noCyclesBetweenModules rejects. provisioning is an
 * orchestration slice that sits above the domain modules: it depends on them and
 * nothing depends on it.
 */
@Service
public class TenantProvisioningService {

    private final TenantRepository tenants;
    private final RoleRepository roles;
    private final UserRoleRepository userRoles;
    private final AppUserRepository users;
    private final TenantConnectionCustomizer binder;
    private final AuditRecorder audit;

    public TenantProvisioningService(TenantRepository tenants, RoleRepository roles,
                                     UserRoleRepository userRoles, AppUserRepository users,
                                     TenantConnectionCustomizer binder, AuditRecorder audit) {
        this.tenants = tenants;
        this.roles = roles;
        this.userRoles = userRoles;
        this.users = users;
        this.binder = binder;
        this.audit = audit;
    }

    /**
     * Platform-admin operation, deliberately NOT gated by a tenant permission:
     * there is no tenant user yet to hold one, and inventing a permission meaning
     * "may create tenants" would put it in the catalog where any tenant role could
     * be granted it. Access is restricted at the /api/platform/** security rule
     * instead. AuthorizationCoverageTest excludes this class by name for exactly
     * this reason.
     */
    @Transactional
    public UUID provision(String slug, String name, String adminEmail, String adminFullName) {
        Tenant tenant = new Tenant();
        tenant.setId(Uuid7.generate());
        tenant.setSlug(slug);
        tenant.setName(name);
        tenant.setStatus(TenantStatus.ACTIVE);
        tenants.save(tenant);

        // Seeding writes to RLS-protected tables, so a tenant must be bound. The
        // previous value is captured and restored in the finally below: TenantContext
        // is a ThreadLocal on a pooled request thread, so leaving it set would hand
        // the next request served by this thread a tenant it never asked for --
        // silently scoping its reads to a stranger's data.
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenant.getId());
        try {
            binder.bind(tenant.getId());
            seedRoles(tenant.getId());

            AppUser admin = new AppUser();
            admin.setId(Uuid7.generate());
            admin.setTenantId(tenant.getId());
            admin.setEmail(adminEmail);
            admin.setFullName(adminFullName);
            admin.setUserType(UserType.INTERNAL);
            // INVITED, with no password hash: the administrator activates via the
            // invitation flow (Task 18). password_hash is nullable precisely for this.
            admin.setStatus(UserStatus.INVITED);
            users.saveAndFlush(admin);

            Role adminRole = roles.findByTenantIdAndName(tenant.getId(), "Administrator")
                    .orElseThrow(() -> new IllegalStateException(
                            "Administrator template was not seeded for tenant " + slug));

            // Bypasses RoleService.assignRole deliberately: that method is gated on
            // USER_MANAGE, and during provisioning there is no actor to hold it.
            // saveAndFlush above is what guarantees app_user exists before this row
            // references it -- user_role.user_id is a foreign key, and without the
            // explicit flush the insert order is left to Hibernate.
            userRoles.save(new UserRole(tenant.getId(), admin.getId(), adminRole.getId()));

            audit.record(AuditActions.TENANT_CREATED, "tenant", tenant.getId(),
                    "Provisioned tenant " + slug, Map.of("slug", slug));
            return tenant.getId();
        } finally {
            if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
        }
    }

    private void seedRoles(UUID tenantId) {
        for (RoleTemplates.RoleTemplate template : RoleTemplates.all()) {
            Role role = new Role();
            role.setId(Uuid7.generate());
            role.setTenantId(tenantId);
            role.setName(template.name());
            role.setDescription(template.description());
            role.setSystemTemplate(true);
            role.setEnabled(true);

            template.grants().forEach((key, scope) -> {
                // Belt and braces against RoleTemplateValidityTest: that test proves the
                // templates are valid at build time, this refuses to seed an invalid one
                // at runtime should a template ever be edited without running it.
                if (!PermissionCatalog.allows(key, scope)) {
                    throw new InvalidGrantException(key, scope);
                }
                RoleGrant grant = new RoleGrant();
                grant.setId(Uuid7.generate());
                grant.setTenantId(tenantId);
                grant.setRole(role);
                grant.setPermissionKey(key);
                grant.setScope(scope);
                role.getGrants().add(grant);
            });

            roles.save(role);
        }
    }
}
