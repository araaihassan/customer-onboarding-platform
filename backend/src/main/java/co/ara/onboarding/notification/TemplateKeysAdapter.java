package co.ara.onboarding.notification;

import co.ara.onboarding.workflow.NotificationTemplateKeys;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Answers workflow's publish rule 7; RLS-bound, so "in this tenant" is the connection's own. */
@Component
class TemplateKeysAdapter implements NotificationTemplateKeys {
    private final JdbcTemplate jdbc;

    TemplateKeysAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean exists(String key) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_template WHERE key = ?)", Boolean.class, key));
    }
}
