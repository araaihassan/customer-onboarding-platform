package co.ara.onboarding.notification;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The only way a notification-class email is sent (invariant 8): a row written in the caller's
 * transaction, delivered later by EmailDispatchJob. A rolled-back caller leaves no row, so nothing
 * is ever emailed about an action that did not happen.
 */
@Component
public class OutboxWriter {

    public record OutboxMessage(OutboxKind kind, String toAddress, UUID recipientUserId, UUID contactId,
                                UUID notificationId, UUID documentRequestId, String subject, String body,
                                String linkPath) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;

    OutboxWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID queue(OutboxMessage m) {
        UUID id = Uuid7.generate();
        Timestamp now = Timestamp.from(Instant.now(clock));
        jdbc.update("""
                INSERT INTO email_outbox (id, tenant_id, kind, to_address, recipient_user_id, contact_id,
                    notification_id, document_request_id, subject, body, link_path, status, attempts,
                    next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)""",
                id, TenantContext.getRequired(), m.kind().name(), m.toAddress(), m.recipientUserId(),
                m.contactId(), m.notificationId(), m.documentRequestId(), m.subject(), m.body(), m.linkPath(),
                now, now, now);
        return id;
    }
}
