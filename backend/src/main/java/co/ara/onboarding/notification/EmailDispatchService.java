package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spec §6.4. claim() leases due rows (FOR UPDATE SKIP LOCKED, so concurrent dispatchers never
 * take the same row) and skips any whose recipient has gone inactive; stamp() records each send's
 * outcome with backoff and a cap. Gated sla.view, the job marker the system actor holds (plan
 * amendment 9); no controller exposes it.
 */
@Service
public class EmailDispatchService {

    public static final int MAX_ATTEMPTS = 5;
    public static final List<Duration> BACKOFF = List.of(
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2));
    static final Duration LEASE = Duration.ofMinutes(5);

    public record Claimed(UUID id, OutboxKind kind, String to, String subject, String body, String linkPath,
                          UUID notificationId, int attempts) {}
    public enum Outcome { SENT, FAILED }
    public record Result(UUID id, Outcome outcome, String error) {}

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditRecorder audit;

    public EmailDispatchService(JdbcTemplate jdbc, Clock clock, AuditRecorder audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Claimed> claim(int limit) {
        Timestamp now = Timestamp.from(Instant.now(clock));
        Timestamp leaseUntil = Timestamp.from(Instant.now(clock).plus(LEASE));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                UPDATE email_outbox o SET status = 'SENDING', lease_until = ?, attempts = o.attempts + 1, updated_at = ?
                 WHERE o.id IN (SELECT id FROM email_outbox
                                 WHERE status IN ('PENDING','SENDING') AND next_attempt_at <= ?
                                   AND (lease_until IS NULL OR lease_until < ?)
                                 ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING o.id, o.kind, o.to_address, o.subject, o.body, o.link_path, o.notification_id,
                          o.attempts, o.recipient_user_id, o.contact_id""",
                leaseUntil, now, now, now, limit);
        List<Claimed> claimed = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            if (!recipientStillActive((UUID) r.get("recipient_user_id"), (UUID) r.get("contact_id"))) {
                jdbc.update("UPDATE email_outbox SET status = 'SKIPPED', lease_until = NULL, updated_at = ? WHERE id = ?",
                        now, id);
                continue;
            }
            claimed.add(new Claimed(id, OutboxKind.valueOf((String) r.get("kind")), (String) r.get("to_address"),
                    (String) r.get("subject"), (String) r.get("body"), (String) r.get("link_path"),
                    (UUID) r.get("notification_id"), ((Number) r.get("attempts")).intValue()));
        }
        return claimed;
    }

    private boolean recipientStillActive(UUID userId, UUID contactId) {
        if (userId != null) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM app_user WHERE id = ? AND status = 'ACTIVE')", Boolean.class, userId));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM customer_contact WHERE id = ? AND status = 'ACTIVE')", Boolean.class, contactId));
    }

    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public void stamp(List<Result> results) {
        Instant at = Instant.now(clock);
        Timestamp now = Timestamp.from(at);
        for (Result r : results) {
            if (r.outcome() == Outcome.SENT) {
                jdbc.update("UPDATE email_outbox SET status = 'SENT', sent_at = ?, lease_until = NULL, last_error = NULL,"
                        + " updated_at = ? WHERE id = ? AND status = 'SENDING'", now, now, r.id());
                jdbc.update("UPDATE notification SET emailed_at = ?, updated_at = ? WHERE id ="
                        + " (SELECT notification_id FROM email_outbox WHERE id = ? AND kind = 'NOTIFICATION')"
                        + " AND emailed_at IS NULL", now, now, r.id());
                continue;
            }
            int attempts = jdbc.queryForObject("SELECT attempts FROM email_outbox WHERE id = ?", Integer.class, r.id());
            String error = r.error() == null ? "unknown" : r.error().substring(0, Math.min(500, r.error().length()));
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE email_outbox SET status = 'FAILED', lease_until = NULL, last_error = ?, updated_at = ?"
                        + " WHERE id = ?", error, now, r.id());
                audit.record(AuditActions.EMAIL_FAILED, "email_outbox", r.id(),
                        "Email delivery failed after " + attempts + " attempts", Map.of("attempts", attempts));
            } else {
                Timestamp next = Timestamp.from(at.plus(BACKOFF.get(attempts - 1)));
                jdbc.update("UPDATE email_outbox SET status = 'PENDING', next_attempt_at = ?, lease_until = NULL,"
                        + " last_error = ?, updated_at = ? WHERE id = ?", next, error, now, r.id());
            }
        }
    }
}
