package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The one place a notification row is inserted. Escalations (sla) call {@link #escalation}
 * directly: spec 5.3 step 5 -- no preference, no visibility drop, always in-app and always an
 * immediate email. Everything else goes through NotificationPipeline (Task 10).
 */
@Component
public class NotificationWriter {

    public record EscalationNotice(UUID recipientUserId, String recipientEmail, String title, String body,
                                   String linkPath, UUID caseId, UUID escalationId) {}

    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final AuditRecorder audit;
    private final Clock clock;

    NotificationWriter(JdbcTemplate jdbc, OutboxWriter outbox, AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID escalation(EscalationNotice n) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO notification (id, tenant_id, recipient_user_id, type, title, body, link_path, case_id,
                    escalation_id, subject_type, subject_id, in_app, email_state, tone, created_at, updated_at)
                VALUES (?, ?, ?, 'ESCALATION', ?, ?, ?, ?, ?, 'case', ?, true, 'QUEUED', 'RISK', ?, ?)""",
                id, TenantContext.getRequired(), n.recipientUserId(), n.title(), n.body(), n.linkPath(),
                n.caseId(), n.escalationId(), n.caseId(), now, now);
        outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, n.recipientEmail(), n.recipientUserId(),
                null, id, null, n.title(), n.body(), n.linkPath()));
        audit.record(AuditActions.NOTIFICATION_SENT, "notification", id, "Escalation notification queued",
                Map.of("escalationId", n.escalationId().toString(), "recipientUserId", n.recipientUserId().toString()));
        return id;
    }

    /** Inserts one row; with a dedupe key, a conflict inserts nothing and returns empty. */
    @Transactional(propagation = Propagation.MANDATORY)
    Optional<UUID> write(NotificationPipeline.Draft d, UUID recipient, boolean inApp, EmailState state, String email) {
        Optional<UUID> id = insert(d, recipient, inApp, state);
        if (id.isEmpty()) return id;
        if (state == EmailState.QUEUED) {
            outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, email, recipient, null, id.get(),
                    null, d.title(), d.body(), d.linkPath()));
        }
        audit.record(AuditActions.NOTIFICATION_SENT, "notification", id.get(), d.type().name() + " notification",
                Map.of("type", d.type().name(), "recipientUserId", recipient.toString(),
                        "subjectType", d.subjectType(), "subjectId", d.subjectId().toString()));
        return id;
    }

    /** Spec 6.2: a consumed lead time -- never in the inbox, never emailed, never audited. */
    @Transactional(propagation = Propagation.MANDATORY)
    void writeMarker(NotificationPipeline.Draft d, UUID recipient) {
        insert(d, recipient, false, EmailState.NONE);
    }

    private Optional<UUID> insert(NotificationPipeline.Draft d, UUID recipient, boolean inApp, EmailState state) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        return jdbc.queryForList("""
                INSERT INTO notification (id, tenant_id, recipient_user_id, type, title, body, link_path, case_id,
                    subject_type, subject_id, in_app, email_state, tone, dedupe_key, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_once_per_dedupe_key DO NOTHING
                RETURNING id""", UUID.class,
                Uuid7.generate(), TenantContext.getRequired(), recipient, d.type().name(), Text.clip(d.title(), 120),
                Text.clip(d.body(), 500), d.linkPath(), d.caseId(), d.subjectType(), d.subjectId(), inApp,
                state.name(), d.tone().name(), d.dedupeKey(), now, now).stream().findFirst();
    }
}
