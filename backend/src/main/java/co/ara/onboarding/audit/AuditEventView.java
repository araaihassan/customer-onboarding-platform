package co.ara.onboarding.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One audit_event row, with its jsonb payload parsed rather than double-encoded as a string. */
public record AuditEventView(UUID id, Instant occurredAt, String actorType, UUID actorUserId,
                             String action, String summary, Map<String, Object> payload,
                             boolean timelineVisible) {}
