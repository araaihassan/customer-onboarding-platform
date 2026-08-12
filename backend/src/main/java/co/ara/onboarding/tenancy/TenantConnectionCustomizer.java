package co.ara.onboarding.tenancy;

import jakarta.persistence.EntityManager;
import org.hibernate.Session;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Component
public class TenantConnectionCustomizer {

    private final EntityManager entityManager;

    public TenantConnectionCustomizer(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Binds the current tenant to both the DB session and the Hibernate filter.
     * MANDATORY is deliberate: set_config(..., true) is transaction-scoped, so
     * binding outside a transaction would silently discard the setting and leave
     * every RLS-protected query returning nothing. Fail loudly instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void bind(UUID tenantId) {
        Session session = entityManager.unwrap(Session.class);
        session.doWork(connection -> {
            try (var stmt = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
                stmt.setString(1, tenantId.toString());
                stmt.execute();
            }
        });
        // "tenantFilter" is declared on the TenantScopedEntity @MappedSuperclass, but
        // Hibernate only registers a @FilterDef with the SessionFactory when some
        // concrete entity actually extends the superclass that declares it. As of
        // Task 4, nothing extends TenantScopedEntity yet, so enabling the filter
        // unconditionally throws UnknownFilterException on every request. Guard it
        // so the primary mechanism -- set_config, which is what RLS reads -- still
        // runs; once a real TenantScopedEntity subclass exists, the filter becomes
        // defined and this starts enabling it with no code change needed here.
        if (session.getSessionFactory().getDefinedFilterNames().contains("tenantFilter")) {
            session.enableFilter("tenantFilter").setParameter("tenantId", tenantId);
        }
    }
}
