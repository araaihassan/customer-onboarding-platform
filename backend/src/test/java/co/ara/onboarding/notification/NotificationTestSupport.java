package co.ara.onboarding.notification;

import co.ara.onboarding.support.PostgresTestBase;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Arrange/assert helpers for the notification tests. Reads go through the owner connection (assertions only). */
@Component
public class NotificationTestSupport {

    private final OutboxWriter outbox;

    NotificationTestSupport(OutboxWriter outbox) { this.outbox = outbox; }

    /** Must run inside fixture.runAs. */
    public UUID queueEmail(UUID tenant, UUID recipientUserId, String to, String subject) {
        return outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.NOTIFICATION, to, recipientUserId, null,
                null, null, subject, "body of " + subject, "/t/x/path"));
    }

    public List<Map<String, Object>> outbox(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from email_outbox where tenant_id = ? order by created_at, id", tenant);
    }

    public List<Map<String, Object>> notifications(UUID tenant) {
        return PostgresTestBase.ownerJdbcForSupport().queryForList(
                "select * from notification where tenant_id = ? order by created_at, id", tenant);
    }
}
