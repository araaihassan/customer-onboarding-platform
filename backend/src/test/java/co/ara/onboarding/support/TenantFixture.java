package co.ara.onboarding.support;

import co.ara.onboarding.tenancy.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.UUID;

@Component
public class TenantFixture {

    private final TenantRepository tenants;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate tx;

    public TenantFixture(TenantRepository tenants,
                         TenantConnectionCustomizer binder,
                         TransactionTemplate tx) {
        this.tenants = tenants;
        this.binder = binder;
        this.tx = tx;
    }

    public UUID createTenant(String slug) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setSlug(slug);
        t.setName(slug);
        t.setStatus(TenantStatus.ACTIVE);
        return tenants.save(t).getId();
    }

    /** Runs the action in a transaction with the tenant bound to both context and connection. */
    public void runAs(UUID tenantId, Runnable action) {
        TenantContext.runAs(tenantId, () -> tx.executeWithoutResult(status -> {
            binder.bind(tenantId);
            action.run();
        }));
    }
}
