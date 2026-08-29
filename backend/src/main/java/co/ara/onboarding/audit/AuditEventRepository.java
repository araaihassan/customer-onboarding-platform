package co.ara.onboarding.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    /**
     * OLDEST FIRST. A case timeline is read as the case's story from the
     * beginning, so page 0 is the earliest events and the newest sit on the
     * last page. This is a product decision, not an incidental one -- flipping
     * it back is a change to the screen, not a refactor.
     *
     * Called only from AuditQuery.findForResource, never directly by a domain
     * service -- see that class's own javadoc for why this is the one place in
     * the codebase a read bypasses AuthorizedQuery. Reversing the current page
     * in the client instead would be wrong: it would order 20 rows within a
     * page while the pages themselves stayed in the opposite order.
     *
     * `id` is the tiebreaker, and it is not decoration. `AuditRecorder` stamps
     * `occurred_at` with `Instant.now()`, so several events recorded inside one
     * request -- which is the normal case, since a single `CaseEngine.reconcile`
     * can satisfy a requirement, complete a milestone and advance a stage -- can
     * share an identical timestamp whenever the clock's resolution is coarser
     * than the work between them. Ordering on `occurred_at` alone leaves those
     * rows in no defined order at all: Postgres may return them differently on
     * each query, so the timeline shuffled events from the same moment.
     *
     * Ids are UUIDv7 (`Uuid7.generate()`), whose leading bits are a millisecond
     * timestamp and whose per-millisecond counter is strictly monotonic, and
     * Postgres compares `uuid` byte-wise -- so `id ASC` breaks the tie in true
     * insertion order rather than merely making it stable.
     *
     * audit_event_tenant_resource_idx is (…, occurred_at DESC); Postgres scans
     * a b-tree in either direction, so ascending order needs no new index.
     */
    Page<AuditEvent> findByResourceTypeAndResourceIdOrderByOccurredAtAscIdAsc(
            String resourceType, UUID resourceId, Pageable pageable);
}
