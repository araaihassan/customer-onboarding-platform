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
     *
     * `id` is the tiebreaker, and it is not decoration. `AuditRecorder` stamps
     * `occurred_at` with `Instant.now()`, so several events recorded inside one
     * request -- which is the normal case, since a single `CaseEngine.reconcile`
     * can satisfy a requirement, complete a milestone and advance a stage -- can
     * share an identical timestamp whenever the clock's resolution is coarser
     * than the work between them. Ordering on `occurred_at` alone leaves those
     * rows in no defined order at all: Postgres may return them differently on
     * each query, so the timeline appeared to shuffle events that happened
     * within the same moment.
     *
     * Ids are UUIDv7 (`Uuid7.generate()`), whose leading bits are a millisecond
     * timestamp, and Postgres compares `uuid` byte-wise -- so `id DESC` breaks
     * the tie in true insertion order rather than merely making it stable.
     */
    Page<AuditEvent> findByResourceTypeAndResourceIdOrderByOccurredAtDescIdDesc(
            String resourceType, UUID resourceId, Pageable pageable);
}
