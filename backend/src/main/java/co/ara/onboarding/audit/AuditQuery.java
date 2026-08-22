package co.ara.onboarding.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * A narrow read over audit_event by exact resource. Takes strings and a UUID and
 * knows nothing about cases, milestones or any other domain type, which is what
 * keeps audit free of a dependency on the modules that write to it -- the same
 * direction that already exists for {@link AuditRecorder}, so no port inversion
 * and no cycle.
 *
 * DOES NOT go through AuthorizedQuery, and that is a documented carve-out from the
 * read invariant -- the only one in the codebase.
 *
 * Justification, which must survive review: the caller resolves the PARENT
 * resource through AuthorizedQuery first and that resolution IS the
 * authorization (see journey.TimelineService), the filter here is an exact
 * (resource_type, resource_id) match rather than a scope-shaped query, and RLS
 * still constrains every row to the bound tenant.
 *
 * The rejected alternative was a generic /audit/timeline endpoint keeping every
 * read inside AuthorizedQuery, which needs a resourceType -> permission-key map
 * whose failure mode is a missing entry defaulting to something.
 *
 * Do not widen this method. A caller that needs events for a SET of resources
 * needs a different mechanism, not another parameter here.
 */
@Component
public class AuditQuery {

    private final AuditEventRepository events;
    private final ObjectMapper json;

    public AuditQuery(AuditEventRepository events, ObjectMapper json) {
        this.events = events;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Page<AuditEventView> findForResource(String resourceType, UUID resourceId, Pageable pageable) {
        return events.findByResourceTypeAndResourceIdOrderByOccurredAtDesc(resourceType, resourceId, pageable)
                .map(this::toView);
    }

    private AuditEventView toView(AuditEvent e) {
        return new AuditEventView(e.getId(), e.getOccurredAt(), e.getActorType(), e.getActorUserId(),
                e.getAction(), e.getSummary(), readPayload(e.getPayload()), e.isTimelineVisible());
    }

    private Map<String, Object> readPayload(String payload) {
        try {
            return json.readValue(payload, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
