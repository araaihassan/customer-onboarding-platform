package co.ara.onboarding.support;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.Department;
import co.ara.onboarding.identity.DepartmentRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final DepartmentRepository departments;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    public TenantFixture(TenantRepository tenants,
                         AppUserRepository users,
                         DepartmentRepository departments,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
        this.departments = departments;
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
        return users.save(u).getId();
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

    /** Runs the action in a transaction with the tenant bound to both context and connection. */
    public void runAs(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
    }
}
