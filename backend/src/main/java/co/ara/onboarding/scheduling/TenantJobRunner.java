package co.ara.onboarding.scheduling;

import co.ara.onboarding.authz.SystemPrincipal;
import co.ara.onboarding.platform.JobLock;
import co.ara.onboarding.tenancy.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs a job body once per ACTIVE tenant (spec §3.4, §1.2.2-§1.2.4): its own transaction, its
 * tenant bound (TenantContext + the RLS GUC), a fresh request scope, the system principal, and a
 * per-(job, tenant) advisory lock. A tenant's failure is logged and never stops the rest.
 */
@Component
public class TenantJobRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantJobRunner.class);

    private final TenantRepository tenants;
    private final TenantConnectionCustomizer binder;
    private final TransactionTemplate isolated;
    private final JobLock lock;

    public TenantJobRunner(TenantRepository tenants, TenantConnectionCustomizer binder,
                           PlatformTransactionManager txManager, JobLock lock) {
        this.tenants = tenants;
        this.binder = binder;
        // REQUIRES_NEW: a caller already inside a transaction (the dev endpoint) must not have every
        // tenant join it, or one failure poisons the rest and the advisory locks outlive the run.
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lock = lock;
    }

    public List<UUID> forEachTenant(String job, Consumer<UUID> body) {
        List<UUID> ran = new ArrayList<>();
        for (Tenant t : tenants.findAll()) {
            if (t.getStatus() != TenantStatus.ACTIVE) continue;
            try {
                if (forTenant(job, t.getId(), body)) ran.add(t.getId());
            } catch (RuntimeException e) {
                log.error("Job {} failed for tenant {}", job, t.getId(), e);
            }
        }
        return ran;
    }

    /**
     * Saves and restores whatever request scope and security context the calling thread already
     * had, so the dev endpoint (Task 21), which calls this from inside a real HTTP request, gets its
     * own user's context back afterwards instead of a cleared one.
     */
    public boolean forTenant(String job, UUID tenantId, Consumer<UUID> body) {
        var previousAttributes = RequestContextHolder.getRequestAttributes();
        var previousSecurity = SecurityContextHolder.getContext();
        var hadAuthentication = previousSecurity.getAuthentication() != null;
        var attributes = new JobRequestAttributes();
        try {
            RequestContextHolder.setRequestAttributes(attributes);
            var jobSecurity = SecurityContextHolder.createEmptyContext();
            jobSecurity.setAuthentication(SystemPrincipal.authentication(tenantId));
            SecurityContextHolder.setContext(jobSecurity);
            return TenantContext.runAsReturning(tenantId, () -> Boolean.TRUE.equals(isolated.execute(status -> {
                binder.bind(tenantId);
                if (!lock.tryLock(job, tenantId)) return false;
                body.accept(tenantId);
                return true;
            })));
        } finally {
            // Restore the thread FIRST: nothing below may leave the system principal on a pooled thread.
            if (hadAuthentication) SecurityContextHolder.setContext(previousSecurity);
            else SecurityContextHolder.clearContext();
            if (previousAttributes == null) RequestContextHolder.resetRequestAttributes();
            else RequestContextHolder.setRequestAttributes(previousAttributes);
            try {
                attributes.complete();
            } catch (RuntimeException e) {
                log.warn("Request-scope cleanup failed for job {} tenant {}", job, tenantId, e);
            }
        }
    }
}
