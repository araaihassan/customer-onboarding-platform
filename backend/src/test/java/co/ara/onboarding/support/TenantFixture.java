package co.ara.onboarding.support;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.identity.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final AppUserRepository users;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    public TenantFixture(TenantRepository tenants,
                         AppUserRepository users,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.users = users;
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

    /** Runs the action in a transaction with the tenant bound to both context and connection. */
    public void runAs(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
    }
}
