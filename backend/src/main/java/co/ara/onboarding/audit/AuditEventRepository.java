package co.ara.onboarding.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * Served by audit_event_tenant_resource_idx (tenant_id, resource_type,
     * resource_id, occurred_at DESC). Called only from AuditQuery.findForResource,
     * never directly by a domain service -- see that class's own javadoc for why
     * this is the one place in the codebase a read bypasses AuthorizedQuery.
     */
    Page<AuditEvent> findByResourceTypeAndResourceIdOrderByOccurredAtDesc(
            String resourceType, UUID resourceId, Pageable pageable);
}
