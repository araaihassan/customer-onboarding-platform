package co.ara.onboarding.support;

import co.ara.onboarding.authz.AuthenticatedPrincipal;
import co.ara.onboarding.customer.Customer;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.UUID;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final TeamRepository teams;
    private final CustomerRepository customers;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    public TenantFixture(TenantRepository tenants,
                         AppUserRepository users,
                         DepartmentRepository departments,
                         TeamRepository teams,
                         CustomerRepository customers,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.departments = departments;
        this.teams = teams;
        this.customers = customers;
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

    /** Runs the action in a transaction with the tenant bound to both context and connection. */
    public void runAs(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
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
            runAs(tenantId, action);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(previousAuth);
            RequestContextHolder.setRequestAttributes(previousRequest);
        }
    }
}
