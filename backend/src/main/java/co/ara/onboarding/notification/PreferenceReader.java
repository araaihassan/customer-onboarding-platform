package co.ara.onboarding.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** Resolves a user's channel preferences (spec 4.2); RLS-bound, so it must run in the caller's tenant transaction. */
@Component
public class PreferenceReader {

    public record Resolved(boolean inApp, boolean email, EmailCadence cadence) {}

    private final JdbcTemplate jdbc;

    PreferenceReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public Resolved resolve(UUID userId, NotificationType type) {
        if (type == NotificationType.ESCALATION) return new Resolved(true, true, EmailCadence.IMMEDIATE);
        var entry = NotificationCatalog.of(type);
        var rows = jdbc.query(
                "SELECT in_app_enabled, email_enabled FROM notification_preference WHERE user_id = ? AND type = ?",
                (rs, i) -> new boolean[]{rs.getBoolean(1), rs.getBoolean(2)}, userId, type.name());
        boolean inApp = rows.isEmpty() ? entry.inAppDefault() : rows.get(0)[0];
        boolean email = rows.isEmpty() ? entry.emailDefault() : rows.get(0)[1];
        return new Resolved(inApp, email, cadence(userId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public EmailCadence cadence(UUID userId) {
        var rows = jdbc.queryForList("SELECT email_cadence FROM notification_settings WHERE user_id = ?",
                String.class, userId);
        return rows.isEmpty() ? EmailCadence.IMMEDIATE : EmailCadence.valueOf(rows.get(0));
    }
}
