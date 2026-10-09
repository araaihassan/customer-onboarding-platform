package co.ara.onboarding.notification;

import co.ara.onboarding.agreement.Agreement;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RecipientAccess;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.document.Document;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.PublicBaseUrl;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.task.Task;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 6B spec 6.3. Daily at 08:00 tenant-local on working days; weekly at 08:00 on the ISO week's
 * first working day; a user who switched back to IMMEDIATE has pending rows flushed as one digest
 * on the next run. Gated sla.view, the job marker (plan amendment 9).
 *
 * <p>A notification's title and body can disclose a record, and a digest is sent later than the
 * row was written, so each pending row is checked again against the recipient's CURRENT grants
 * ({@link RecipientAccess}, the pipeline's own gate) and one they can no longer see is left
 * DIGEST_PENDING, never emailed. Only ACTIVE INTERNAL users are candidates at all. The run executes
 * under the per-tenant advisory lock, and every row is moved DIGEST_PENDING to DIGESTED in the same
 * transaction that queues the email, so a row is in at most one digest and a failed send is retried
 * by the outbox, never re-batched.
 */
@Service
public class DigestService {

    static final LocalTime SEND_AT = LocalTime.of(8, 0);

    private final JdbcTemplate jdbc;
    private final BusinessCalendar calendar;
    private final Clock clock;
    private final OutboxWriter outbox;
    private final PublicBaseUrl baseUrl;
    private final SubjectFacts facts;
    private final RecipientAccess access;

    DigestService(JdbcTemplate jdbc, BusinessCalendar calendar, Clock clock, OutboxWriter outbox,
                  PublicBaseUrl baseUrl, SubjectFacts facts, RecipientAccess access) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.clock = clock;
        this.outbox = outbox;
        this.baseUrl = baseUrl;
        this.facts = facts;
        this.access = access;
    }

    private record Candidate(UUID userId, EmailCadence cadence, Instant lastDigestAt) {}

    /** Returns how many digests were queued. */
    @RequirePermission(PermissionKeys.SLA_VIEW)
    @Transactional(propagation = Propagation.MANDATORY)
    public int run() {
        Instant now = Instant.now(clock);
        LocalDate today = calendar.today();
        Instant sendAt = calendar.startOfDay(today).plus(Duration.between(LocalTime.MIDNIGHT, SEND_AT));
        boolean workingDay = calendar.businessDaysBetween(today, today.plusDays(1)) == 1;
        // No working day in [Monday, today) means today is the week's first working day.
        boolean firstWorkingDayOfWeek = workingDay && calendar.businessDaysBetween(
                today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today) == 0;
        boolean pastSendTime = !now.isBefore(sendAt);
        int queued = 0;
        for (Candidate c : candidates()) {
            boolean due = switch (c.cadence()) {
                case IMMEDIATE -> true;                                     // a switched user's leftovers
                case DAILY -> workingDay && pastSendTime && (c.lastDigestAt() == null || c.lastDigestAt().isBefore(sendAt));
                case WEEKLY -> firstWorkingDayOfWeek && pastSendTime && (c.lastDigestAt() == null || c.lastDigestAt().isBefore(sendAt));
            };
            if (!due) continue;
            if (send(c, now)) queued++;
        }
        return queued;
    }

    /** ACTIVE INTERNAL users with pending rows, plus daily/weekly users (so last_digest_at advances on an empty day). */
    private List<Candidate> candidates() {
        return jdbc.query("""
                SELECT u.id, COALESCE(s.email_cadence, 'IMMEDIATE'), s.last_digest_at
                  FROM app_user u LEFT JOIN notification_settings s ON s.user_id = u.id
                 WHERE u.status = 'ACTIVE' AND u.user_type = 'INTERNAL'
                   AND (u.id IN (SELECT recipient_user_id FROM notification WHERE email_state = 'DIGEST_PENDING')
                        OR s.email_cadence IN ('DAILY','WEEKLY'))
                 ORDER BY u.id""",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class), EmailCadence.valueOf(rs.getString(2)),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant()));
    }

    private boolean send(Candidate c, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                INSERT INTO notification_settings (id, tenant_id, user_id, email_cadence, last_digest_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_settings_one_per_user
                DO UPDATE SET last_digest_at = EXCLUDED.last_digest_at, updated_at = EXCLUDED.updated_at""",
                Uuid7.generate(), TenantContext.getRequired(), c.userId(), c.cadence().name(), at, at, at);
        Optional<String> email = facts.activeInternalEmail(c.userId());
        if (email.isEmpty()) return false;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var r : jdbc.queryForList("""
                SELECT id, type, title, link_path, subject_type, subject_id, case_id FROM notification
                 WHERE recipient_user_id = ? AND email_state = 'DIGEST_PENDING' ORDER BY type, id DESC""", c.userId())) {
            if (stillVisible(c.userId(), r)) rows.add(r);
        }
        if (rows.isEmpty()) return false;
        String heading = switch (c.cadence()) {
            case DAILY -> "Your daily digest: ";
            case WEEKLY -> "Your weekly digest: ";
            case IMMEDIATE -> "Your pending notifications: ";   // a user who switched back to immediate
        };
        StringBuilder body = new StringBuilder("Your notifications since your last digest:\n");
        String lastType = null;
        for (var r : rows) {
            String type = (String) r.get("type");
            if (!type.equals(lastType)) {
                body.append("\n").append(NotificationCatalog.of(NotificationType.valueOf(type)).label()).append("\n");
                lastType = type;
            }
            body.append("- ").append(r.get("title")).append("\n  ").append(baseUrl.absolute((String) r.get("link_path"))).append("\n");
        }
        UUID outboxId = outbox.queue(new OutboxWriter.OutboxMessage(OutboxKind.DIGEST, email.get(), c.userId(), null, null,
                null, heading + rows.size() + " notification" + (rows.size() == 1 ? "" : "s"),
                body.toString(), null));
        for (var r : rows) {
            jdbc.update("INSERT INTO email_outbox_item (outbox_id, notification_id, tenant_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                    outboxId, r.get("id"), TenantContext.getRequired(), at, at);
            jdbc.update("UPDATE notification SET email_state = 'DIGESTED', updated_at = ? WHERE id = ? AND email_state = 'DIGEST_PENDING'",
                    at, r.get("id"));
        }
        return true;
    }

    /**
     * The recipient's CURRENT access to what the row is about: the subject under its own permission
     * where the pipeline gated on it, otherwise the case. A row with nothing to check against fails closed.
     */
    private boolean stillVisible(UUID userId, Map<String, Object> row) {
        String subjectType = (String) row.get("subject_type");
        UUID subjectId = (UUID) row.get("subject_id");
        UUID caseId = (UUID) row.get("case_id");
        if (subjectType != null) {
            switch (subjectType) {
                case "task" -> { return access.canView(userId, PermissionKeys.TASK_VIEW, Task.class, subjectId); }
                case "document" -> { return access.canView(userId, PermissionKeys.DOCUMENT_VIEW, Document.class, subjectId); }
                case "agreement" -> { return access.canView(userId, PermissionKeys.AGREEMENT_VIEW, Agreement.class, subjectId); }
                case "customer" -> { return access.canView(userId, PermissionKeys.CUSTOMER_VIEW, Customer.class, subjectId); }
                default -> { }
            }
        }
        return caseId != null && access.canView(userId, PermissionKeys.CASE_VIEW, Case.class, caseId);
    }
}
