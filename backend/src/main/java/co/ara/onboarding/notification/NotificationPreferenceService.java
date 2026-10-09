package co.ara.onboarding.notification;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The caller's own notification preferences (6B spec 8). NOT permission-gated, on InboxService's
 * (and MeService's) basis: it only ever reads and writes rows whose user_id is the caller, and it
 * takes no id from the request at all. Writes are RLS-bound upserts in the caller's tenant.
 *
 * The PUT is a full replace (plan amendment 11): every opt-out type must be listed exactly once,
 * so an omitted type is a 400 rather than a silent reset. ESCALATION is locked by policy (QA Q10):
 * listing it with a channel off is a 422; listing it with both on, or not at all, is accepted and
 * stores nothing.
 */
@Service
public class NotificationPreferenceService {

    private final JdbcTemplate jdbc;
    private final AuthContextProvider contexts;
    private final PreferenceReader prefs;
    private final AuditRecorder audit;
    private final Clock clock;

    public NotificationPreferenceService(JdbcTemplate jdbc, AuthContextProvider contexts, PreferenceReader prefs,
                                         AuditRecorder audit, Clock clock) {
        this.jdbc = jdbc;
        this.contexts = contexts;
        this.prefs = prefs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public PreferencesView get() {
        UUID me = contexts.principal().userId();
        List<TypePreferenceView> types = NotificationCatalog.all().stream().map(e -> {
            var r = prefs.resolve(me, e.type());
            return new TypePreferenceView(e.type(), e.label(), r.inApp(), r.email(), e.locked());
        }).toList();
        return new PreferencesView(prefs.cadence(me), types);
    }

    @Transactional
    public PreferencesView replace(UpdatePreferencesRequest r) {
        UUID me = contexts.principal().userId();
        Map<NotificationType, TypePreferenceRequest> byType = new EnumMap<>(NotificationType.class);
        for (TypePreferenceRequest t : r.types()) {
            if (byType.put(t.type(), t) != null) throw new IllegalArgumentException("Each notification type may be listed once");
        }
        TypePreferenceRequest esc = byType.remove(NotificationType.ESCALATION);
        if (esc != null && !(esc.inApp() && esc.email())) {
            throw new NotificationRuleException(
                    "Escalation to your manager is required by policy and cannot be turned off");
        }
        if (!byType.keySet().equals(EnumSet.copyOf(NotificationCatalog.optOut().stream().map(NotificationCatalog.Entry::type).toList()))) {
            throw new IllegalArgumentException("Every notification type must be listed");
        }
        Timestamp now = Timestamp.from(Instant.now(clock));
        UUID tenant = TenantContext.getRequired();
        for (TypePreferenceRequest t : byType.values()) {
            jdbc.update("""
                INSERT INTO notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT notification_preference_once
                DO UPDATE SET in_app_enabled = EXCLUDED.in_app_enabled, email_enabled = EXCLUDED.email_enabled, updated_at = EXCLUDED.updated_at""",
                Uuid7.generate(), tenant, me, t.type().name(), t.inApp(), t.email(), now, now);
        }
        jdbc.update("""
            INSERT INTO notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT ON CONSTRAINT notification_settings_one_per_user
            DO UPDATE SET email_cadence = EXCLUDED.email_cadence, updated_at = EXCLUDED.updated_at""",
            Uuid7.generate(), tenant, me, r.emailCadence().name(), now, now);
        audit.record(AuditActions.NOTIFICATION_PREFERENCES_CHANGED, "app_user", me,
                "Changed notification preferences", Map.of("emailCadence", r.emailCadence().name()));
        return get();
    }
}
