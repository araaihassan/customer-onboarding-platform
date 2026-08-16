package co.ara.onboarding.tenancy;

import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.UUID;

/**
 * Applies the current tenant to the JDBC connection at the start of every
 * transactional method. set_config(..., true) is transaction-scoped, so
 * a pooled connection cannot carry a tenant into the next request.
 */
@Aspect
@Component
@Order(200)   // MUST be a larger number than the transaction advisor's order — see TransactionConfig
public class TenantTransactionBinder {

    private final TenantConnectionCustomizer binder;

    public TenantTransactionBinder(TenantConnectionCustomizer binder) { this.binder = binder; }

    // Excludes TenantConnectionCustomizer itself: bind() is @Transactional(MANDATORY),
    // so without this exclusion the pointcut would also match bind()'s own invocation,
    // re-firing this advice and calling bind() again — infinite recursion /
    // StackOverflowError. Confirmed empirically: removing the exclusion reproduces it.
    @Before("(@annotation(org.springframework.transaction.annotation.Transactional) "
          + "|| @within(org.springframework.transaction.annotation.Transactional)) "
          + "&& !within(co.ara.onboarding.tenancy.TenantConnectionCustomizer)")
    public void bindTenant() {
        UUID tenantId = TenantContext.getOrNull();
        if (tenantId != null) binder.bind(tenantId);
    }
}
