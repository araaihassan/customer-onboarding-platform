package co.ara.onboarding.support;

import co.ara.onboarding.authz.AuthenticatedPrincipal;
import co.ara.onboarding.authz.Permission;
import co.ara.onboarding.authz.PermissionCatalog;
import co.ara.onboarding.authz.Role;
import co.ara.onboarding.authz.RoleGrant;
import co.ara.onboarding.authz.RoleRepository;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.authz.UserRole;
import co.ara.onboarding.authz.UserRoleRepository;
import co.ara.onboarding.auth.InvitationService;
import co.ara.onboarding.customer.ContactStatus;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerContact;
import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.customer.CustomerStatus;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.Department;
import co.ara.onboarding.identity.DepartmentRepository;
import co.ara.onboarding.identity.Team;
import co.ara.onboarding.identity.TeamRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final CustomerRepository customers;
    private final CustomerContactRepository contacts;
    private final InvitationService invitations;
    private final jakarta.persistence.EntityManager entityManager;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwords;
    private final UserRoleRepository userRoleRepository;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    /** One fixture administrator per tenant, created on first privileged use. */
    private final Map<UUID, UUID> administrators = new ConcurrentHashMap<>();

    public TenantFixture(TenantRepository tenants,
                         AppUserRepository users,
                         DepartmentRepository departments,
                         TeamRepository teams,
                         CustomerRepository customers,
                         CustomerContactRepository contacts,
                         InvitationService invitations,
                         jakarta.persistence.EntityManager entityManager,
                         RoleRepository roleRepository,
                         PasswordEncoder passwords,
                         UserRoleRepository userRoleRepository,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.departments = departments;
        this.teams = teams;
        this.customers = customers;
        this.contacts = contacts;
        this.invitations = invitations;
        this.entityManager = entityManager;
        this.roleRepository = roleRepository;
        this.passwords = passwords;
        this.userRoleRepository = userRoleRepository;
        this.binder = binder;
        this.tx = tx;
    }

    public UUID createTenant(String slug) {
        Tenant t = new Tenant();
        t.setId(Uuid7.generate());
        t.setSlug(slug);
        t.setName(slug);
        t.setStatus(TenantStatus.ACTIVE);
        return tenants.save(t).getId();
    }

    /**
     * A minimal ACTIVE internal user. The password hash is a placeholder, not a
     * real Argon2id hash -- nothing here authenticates, and Task 15 is what
     * introduces real hashing. Must be called inside {@link #runAs} so the
     * tenant is bound: app_user is RLS-protected, and an unbound insert fails the
     * policy's WITH CHECK rather than silently writing.
     *
     * saveAndFlush, like the other create helpers: user_role, team_member and
     * customer all carry foreign keys to app_user, and flushing here means the row
     * exists before any referencing insert rather than relying on Hibernate's
     * insert ordering.
     */
    public UUID createUser(UUID tenantId, String email) {
        AppUser u = new AppUser();
        u.setId(Uuid7.generate());
        u.setTenantId(tenantId);
        u.setEmail(email);
        u.setFullName(email);
        u.setPasswordHash("x");
        u.setUserType(UserType.INTERNAL);
        u.setStatus(UserStatus.ACTIVE);
        return users.saveAndFlush(u).getId();
    }

    /**
     * A real Argon2id-hashed password, unlike {@link #createUser}'s placeholder.
     *
     * Unlike the other create helpers, this one binds the tenant itself rather than
     * requiring an enclosing runAs — login tests have no other reason to open one,
     * and the returned AppUser is detached on purpose so they can read its id and
     * tenant outside the transaction. Nesting inside an existing runAs is still
     * safe: the TransactionTemplate joins the outer transaction.
     */
    public AppUser createUserWithPassword(UUID tenantId, String email, String rawPassword) {
        return createUserWithPassword(tenantId, email, rawPassword, false);
    }

    public AppUser createUserWithPassword(UUID tenantId, String email,
                                          String rawPassword, boolean mfaEnabled) {
        return savePasswordUser(tenantId, email, rawPassword, UserStatus.ACTIVE, mfaEnabled);
    }

    /** INVITED, so it must not be able to log in even with the right password. */
    public AppUser createInvitedUser(UUID tenantId, String email, String rawPassword) {
        return savePasswordUser(tenantId, email, rawPassword, UserStatus.INVITED, false);
    }

    private AppUser savePasswordUser(UUID tenantId, String email, String rawPassword,
                                     UserStatus status, boolean mfaEnabled) {
        var saved = new AtomicReference<AppUser>();
        runUnauthenticated(tenantId, () -> {
            AppUser u = new AppUser();
            u.setId(Uuid7.generate());
            u.setTenantId(tenantId);
            u.setEmail(email);
            u.setFullName(email);
            u.setPasswordHash(passwords.encode(rawPassword));
            u.setUserType(UserType.INTERNAL);
            u.setStatus(status);
            u.setMfaEnabled(mfaEnabled);
            saved.set(users.saveAndFlush(u));
        });
        return saved.get();
    }

    /**
     * A department, flushed immediately. Callers generally need the id as a foreign
     * key on another row — customer.owning_department_id references department(id)
     * — and without the flush the referencing insert can reach the database first.
     * Must be called inside {@link #runAs}.
     */
    public UUID createDepartment(UUID tenantId, String name) {
        Department d = new Department();
        d.setId(Uuid7.generate());
        d.setTenantId(tenantId);
        d.setName(name);
        return departments.saveAndFlush(d).getId();
    }

    /** Must be called inside {@link #runAs}. */
    public UUID createTeam(UUID tenantId, String name) {
        Team t = new Team();
        t.setId(Uuid7.generate());
        t.setTenantId(tenantId);
        t.setName(name);
        return teams.saveAndFlush(t).getId();
    }

    /**
     * Adds a team membership by loading the user and mutating its teamIds
     * collection, so the composite join mapping populates team_member.tenant_id
     * — writing the row directly would have to duplicate that knowledge.
     * Must be called inside {@link #runAs}.
     */
    public void addToTeam(UUID tenantId, UUID userId, UUID teamId) {
        AppUser user = users.findById(userId).orElseThrow();
        user.getTeamIds().add(teamId);
        users.saveAndFlush(user);
    }

    /**
     * A customer with explicit ownership columns, which is what the DEPARTMENT,
     * TEAM and ASSIGNED scopes resolve against. Any of the three may be null.
     * Must be called inside {@link #runAs}.
     */
    public UUID createCustomer(UUID tenantId, String displayName,
                              UUID ownerUserId, UUID departmentId, UUID teamId) {
        Customer c = new Customer();
        c.setId(Uuid7.generate());
        c.setTenantId(tenantId);
        c.setLegalName(displayName + " Ltd");
        c.setDisplayName(displayName);
        c.setStatus(CustomerStatus.PROSPECT);
        c.setOwnerUserId(ownerUserId);
        c.setOwningDepartmentId(departmentId);
        c.setOwningTeamId(teamId);
        return customers.saveAndFlush(c).getId();
    }

    /** An ACTIVE contact with no linked portal user yet. Must be called inside {@link #runAs}. */
    public UUID createContact(UUID tenantId, UUID customerId, String email) {
        CustomerContact c = new CustomerContact();
        c.setId(Uuid7.generate());
        c.setTenantId(tenantId);
        c.setCustomerId(customerId);
        c.setFullName(email);
        c.setEmail(email);
        c.setStatus(ContactStatus.ACTIVE);
        return contacts.saveAndFlush(c).getId();
    }

    /** Issues an activation invitation through the gated service. Inside {@link #runAs}. */
    public String issueInvitation(UUID contactId) {
        return invitations.issue(contactId);
    }

    /**
     * Ages every invitation past its expiry, so expiry can be tested without waiting
     * seven days. A bulk UPDATE rather than entity mutation, then a clear, so the
     * service reads the aged rows rather than cached ones.
     */
    public void expireInvitations() {
        entityManager.createNativeQuery(
                "UPDATE invitation SET expires_at = now() - interval '1 day'").executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * Tenant bound, NO authenticated user. The primitive the other two build on.
     *
     * Use this only where the absence of a principal is the point — bootstrapping
     * the fixture's own administrator, or asserting unauthenticated behaviour.
     * Anything calling a @RequirePermission service through this will be denied,
     * because there is no principal to resolve permissions for.
     */
    public void runUnauthenticated(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
    }

    /**
     * Runs with a tenant bound AND an authenticated administrator, so gated
     * services work in setup code.
     *
     * This became privileged when Task 14 activated the permission gate. Setup
     * should be privileged; only assertions should be scope-constrained. Tests
     * asserting authorization behaviour must use {@link #runAsUser} with a specific
     * user instead — using this one would grant them everything and prove nothing.
     */
    public void runAs(UUID tenantId, Runnable action) {
        runAsUser(tenantId, administratorFor(tenantId), action);
    }

    /**
     * The fixture's administrator for a tenant, created once per tenant.
     *
     * Reuses the seeded "Administrator" role when the tenant was provisioned
     * through TenantProvisioningService, and only creates its own role otherwise.
     * That matters for assertions that count roles: a fixture role added to a
     * provisioned tenant would make its twelve templates thirteen.
     *
     * The role is written through the repositories rather than RoleService because
     * RoleService.createRole is now gated on ROLE_MANAGE — bootstrapping the
     * administrator through the gate would require an administrator.
     */
    private UUID administratorFor(UUID tenantId) {
        return administrators.computeIfAbsent(tenantId, t -> {
            var userId = new AtomicReference<UUID>();
            runUnauthenticated(t, () -> {
                userId.set(createUser(t, "fixture-admin+" + t + "@fixture.test"));
                UUID roleId = roleRepository.findByTenantIdAndName(t, "Administrator")
                        .map(Role::getId)
                        .orElseGet(() -> createSuperuserRole(t));
                userRoleRepository.saveAndFlush(new UserRole(t, userId.get(), roleId));
            });
            return userId.get();
        });
    }

    /**
     * A role granting every catalogued permission at ALL scope. Every permission in
     * the catalog allows ALL, so this needs no per-permission filtering.
     * systemTemplate stays false and the name is distinctive, so nothing mistakes
     * it for one of the twelve seeded templates.
     */
    private UUID createSuperuserRole(UUID tenantId) {
        Role role = new Role();
        role.setId(Uuid7.generate());
        role.setTenantId(tenantId);
        role.setName("Fixture Superuser");
        role.setDescription("Test fixture only");
        role.setSystemTemplate(false);
        role.setEnabled(true);

        for (Permission p : PermissionCatalog.all()) {
            RoleGrant grant = new RoleGrant();
            grant.setId(Uuid7.generate());
            grant.setTenantId(tenantId);
            grant.setRole(role);
            grant.setPermissionKey(p.key());
            grant.setScope(Scope.ALL);
            role.getGrants().add(grant);
        }
        return roleRepository.saveAndFlush(role).getId();
    }

    /**
     * Runs the action as an authenticated user in a FRESH request scope.
     *
     * The fresh scope is the point, not incidental. AuthorizationService is
     * @RequestScope and memoizes the resolved permissions in a field, so two calls
     * to this method produce two instances and therefore two independent
     * resolutions — which is what makes the "takes effect on the next request"
     * assertions mean anything. Reusing one scope would memoize across both halves
     * and the tests would pass while proving the opposite.
     *
     * Previous request attributes and authentication are captured and restored
     * rather than cleared: Spring's ServletTestExecutionListener installs a request
     * scope for the whole test method, and clearing it outright would break any
     * @RequestScope bean used later in the same test — RequestAuditContext, for
     * one, which AuditRecorder reads on every write.
     */
    public void runAsUser(UUID tenantId, UUID userId, Runnable action) {
        RequestAttributes previousRequest = RequestContextHolder.getRequestAttributes();
        Authentication previousAuth = SecurityContextHolder.getContext().getAuthentication();

        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedPrincipal(tenantId, userId), null, List.of()));
        try {
            // runUnauthenticated, not runAs: runAs delegates here, so calling it would
            // recurse forever.
            runUnauthenticated(tenantId, action);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previousAuth);
            RequestContextHolder.setRequestAttributes(previousRequest);
        }
    }
}
