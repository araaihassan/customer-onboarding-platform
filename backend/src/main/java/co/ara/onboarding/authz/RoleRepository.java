package co.ara.onboarding.authz;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Rows are constrained twice: PostgreSQL RLS on role, and the Hibernate
 * tenantFilter inherited from TenantScopedEntity. Neither is applied unless a
 * tenant is bound, which is why repository calls must happen inside a
 * @Transactional service method — see ModuleBoundaryTest's
 * controllersDoNotUseRepositoriesDirectly.
 */
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByName(String name);

    /**
     * tenant_id is redundant against RLS and the Hibernate filter, which both
     * already constrain the result. It is named explicitly anyway so provisioning
     * — which seeds roles for a tenant other than any request's — cannot depend on
     * whichever tenant happens to be bound.
     */
    Optional<Role> findByTenantIdAndName(UUID tenantId, String name);
}
