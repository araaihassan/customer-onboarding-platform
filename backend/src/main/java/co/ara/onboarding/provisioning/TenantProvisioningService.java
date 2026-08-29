package co.ara.onboarding.provisioning;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.auth.Invitation;
import co.ara.onboarding.auth.InvitationPurpose;
import co.ara.onboarding.auth.InvitationRepository;
import co.ara.onboarding.auth.InvitationService;
import co.ara.onboarding.auth.SecureTokens;
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
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.Tenant;
import co.ara.onboarding.tenancy.TenantConnectionCustomizer;
import co.ara.onboarding.tenancy.TenantContext;
import co.ara.onboarding.tenancy.TenantRepository;
import co.ara.onboarding.tenancy.TenantStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
    private final InvitationRepository invitations;
    private final EmailSender email;
    private final TenantConnectionCustomizer binder;
    private final AuditRecorder audit;

    public TenantProvisioningService(TenantRepository tenants, RoleRepository roles,
                                     UserRoleRepository userRoles, AppUserRepository users,
                                     InvitationRepository invitations, EmailSender email,
                                     TenantConnectionCustomizer binder, AuditRecorder audit) {
        this.tenants = tenants;
        this.roles = roles;
        this.userRoles = userRoles;
        this.users = users;
        this.invitations = invitations;
        this.email = email;
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
    /**
     * The whole method body is inside this single try/catch, not just the
     * {@code tenants.save} call, because BaseEntity has no {@code @Version} and no
     * {@code Persistable}, so an already-ID-assigned Tenant routes through
     * {@code entityManager.merge()} rather than {@code persist()} -- and Hibernate
     * does not necessarily flush a merge immediately. Empirically (see
     * TenantProvisioningTest), the duplicate-slug constraint violation actually
     * surfaces at the {@code users.saveAndFlush(admin)} call below, not at
     * {@code tenants.save(tenant)} itself. Catching broadly here rather than
     * chasing the exact flush point is safe: a duplicate admin email cannot occur
     * within a single fresh call (the admin user row is always new), so the only
     * DataIntegrityViolationException a single provision() call can produce is the
     * tenant.slug one.
     */
    @Transactional
    public UUID provision(String slug, String name, String adminEmail, String adminFullName) {
        try {
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

                inviteAdministrator(tenant.getId(), admin.getId(), adminEmail);

                audit.record(AuditActions.TENANT_CREATED, "tenant", tenant.getId(),
                        "Provisioned tenant " + slug, Map.of("slug", slug));
                return tenant.getId();
            } finally {
                if (previous == null) TenantContext.clear(); else TenantContext.set(previous);
            }
        } catch (DataIntegrityViolationException e) {
            if (violates(e, TENANT_SLUG_UNIQUE)) throw new DuplicateSlugException(e);
            // Every other constraint is rethrown untouched. Reporting an unrelated
            // violation as "that slug is taken" would send the caller hunting for a
            // duplicate that does not exist.
            throw e;
        }
    }

    /** Postgres's generated name for {@code UNIQUE} on tenant.slug in V2. */
    private static final String TENANT_SLUG_UNIQUE = "tenant_slug_key";

    /**
     * Matched on the constraint name Hibernate reports rather than on the message
     * text, which is Postgres's to reword.
     */
    private static boolean violates(Throwable failure, String constraintName) {
        for (Throwable t = failure; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve
                    && constraintName.equals(cve.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bootstrap ACTIVATION invitation, without which a freshly provisioned
     * tenant cannot be logged into at all.
     *
     * The administrator is created INVITED with no password hash, LoginService
     * admits only ACTIVE, and only ActivationService.accept promotes INVITED ->
     * ACTIVE — against an ACTIVATION invitation. UserInvitationService.issueForUser
     * creates exactly that row but is gated on user.manage, and the only account
     * that would hold it is the one being invited. So provisioning issues it here,
     * beside the app_user and user_role rows it already writes outside the gate for
     * the same bootstrap reason, and in the same transaction: an invitation that
     * outlived a rolled-back tenant would be a live credential for nothing.
     *
     * SecureRandom via SecureTokens, never Uuid7 — this is a bearer credential, and
     * a UUIDv7 leaks its creation time and is guessable from a neighbour. Only the
     * hash is stored.
     *
     * The raw token is deliberately NOT returned to the caller: it travels by email
     * like every other invitation in this system, and under the dev and test
     * profiles LoggingEmailSender puts it in the application log where operators and
     * the end-to-end suite read it (see CLAUDE.md).
     *
     * Known and accepted: there is no re-issue path once ACTIVATION_TTL elapses, so
     * a tenant provisioned and then forgotten for seven days needs manual
     * intervention. Tenant administration is a sub-project 2 concern.
     */
    private void inviteAdministrator(UUID tenantId, UUID adminId, String adminEmail) {
        String raw = SecureTokens.generate();

        Invitation invitation = new Invitation();
        invitation.setId(Uuid7.generate());
        invitation.setTenantId(tenantId);
        invitation.setPurpose(InvitationPurpose.ACTIVATION);
        invitation.setUserId(adminId);
        invitation.setTokenHash(SecureTokens.hash(raw));
        invitation.setExpiresAt(Instant.now().plus(InvitationService.ACTIVATION_TTL));
        // created_by stays null: provisioning has no actor, which is the whole
        // reason this row cannot be issued through the gated service.
        invitations.save(invitation);

        email.send(new EmailMessage(adminEmail, "Activate your account",
                "Use this token to activate your account: " + raw));
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
