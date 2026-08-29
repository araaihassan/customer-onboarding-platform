package co.ara.onboarding.audit;

import co.ara.onboarding.platform.RequestAuditContext;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Component
public class AuditRecorder {

    private final AuditEventRepository events;
    private final RequestAuditContext context;
    private final ObjectMapper json;

    public AuditRecorder(AuditEventRepository events,
                         RequestAuditContext context,
                         ObjectMapper json) {
        this.events = events;
        this.context = context;
        this.json = json;
    }

    /**
     * MANDATORY is deliberate: an audit event must be written in the same
     * transaction as the change it records, so a rolled-back operation cannot
     * leave a phantom audit entry.
     *
     * CALL ORDER IS THE TIMELINE ORDER. occurredAt is stamped here, from the
     * clock, so events are ordered by when record() ran -- not by when the
     * change they describe conceptually happened. An action that triggers
     * further recorded work must therefore record ITSELF FIRST, before
     * calling into that work.
     *
     * This is easy to get backwards, because writing the audit line last reads
     * naturally ("do the thing, then record it") and every unit test passes
     * either way. Nine call sites across five journey services had it that way:
     * each recorded its cause after engine.reconcile(), so case.created landed
     * ABOVE the case.stage_entered and milestone.completed of its own creation
     * on a newest-first timeline -- a case that appears to have been created
     * after the milestones inside it finished. Found by reading the screen, not
     * by a test.
     *
     * journey.CauseBeforeEffectTest guards it now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditAction action, String resourceType, UUID resourceId,
                       String summary, Object payload) {
        AuditEvent e = new AuditEvent();
        e.setId(Uuid7.generate());
        e.setTenantId(TenantContext.getRequired());
        e.setOccurredAt(Instant.now());
        e.setAction(action.key());
        e.setTimelineVisible(action.timelineVisible());
        e.setResourceType(resourceType);
        e.setResourceId(resourceId);
        e.setSummary(summary);
        e.setPayload(writeJson(payload));
        e.setActorUserId(context.actorUserId());
        e.setActorType(context.actorType().name());
        e.setIp(context.ip());
        e.setUserAgent(context.userAgent());
        e.setRequestId(context.requestId());
        events.save(e);
    }

    private String writeJson(Object payload) {
        try {
            return payload == null ? "{}" : json.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Audit payload is not serializable", ex);
        }
    }
}
