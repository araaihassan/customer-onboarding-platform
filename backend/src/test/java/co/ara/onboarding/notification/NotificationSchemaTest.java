package co.ara.onboarding.notification;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationSchemaTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;

    @Test
    void everyNotificationTypeIsAllowedByTheCheck() {
        UUID t = fixture.createTenant("ns-types");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-types.test"));
        for (NotificationType type : NotificationType.values()) {
            ownerJdbc().update("""
                insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path,
                    subject_type, subject_id, in_app, email_state, tone, created_at, updated_at)
                values (gen_random_uuid(), ?, ?, ?, 't', 'b', '/x', 'case', gen_random_uuid(), true, 'NONE', 'INFO', now(), now())""",
                t, u, type.name());
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from notification where tenant_id = ?", Long.class, t))
                .isEqualTo(NotificationType.values().length);
    }

    @Test
    void aDedupeKeyIsUniquePerRecipientButNullsAreNot() {
        UUID t = fixture.createTenant("ns-dedupe");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-dedupe.test"));
        String insert = """
            insert into notification (id, tenant_id, recipient_user_id, type, title, body, link_path,
                subject_type, subject_id, in_app, email_state, tone, dedupe_key, created_at, updated_at)
            values (gen_random_uuid(), ?, ?, 'TASK_OVERDUE', 't', 'b', '/x', 'task', gen_random_uuid(), true, 'NONE', 'WARN', ?, now(), now())""";
        ownerJdbc().update(insert, t, u, null);
        ownerJdbc().update(insert, t, u, null);                       // NULLs are distinct
        ownerJdbc().update(insert, t, u, "TASK_OVERDUE:x:2026-10-05");
        assertThatThrownBy(() -> ownerJdbc().update(insert, t, u, "TASK_OVERDUE:x:2026-10-05"))
                .hasMessageContaining("notification_once_per_dedupe_key");
    }

    @Test
    void anOutboxRowNamesExactlyOneRecipient() {
        UUID t = fixture.createTenant("ns-outbox");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@ns-outbox.test"));
        assertThatThrownBy(() -> ownerJdbc().update("""
            insert into email_outbox (id, tenant_id, kind, to_address, subject, body, status, attempts,
                next_attempt_at, created_at, updated_at)
            values (gen_random_uuid(), ?, 'NOTIFICATION', 'a@b.c', 's', 'b', 'PENDING', 0, now(), now(), now())""", t))
                .hasMessageContaining("email_outbox_one_recipient_ck");
        ownerJdbc().update("""
            insert into email_outbox (id, tenant_id, kind, to_address, recipient_user_id, subject, body, status,
                attempts, next_attempt_at, created_at, updated_at)
            values (gen_random_uuid(), ?, 'NOTIFICATION', 'a@b.c', ?, 's', 'b', 'PENDING', 0, now(), now(), now())""", t, u);
    }

    @Test
    void theApplicationRoleCannotDeleteNotificationsOrOutboxRows() {
        withAppConnection(jdbc -> {
            for (String table : new String[]{"notification", "email_outbox", "email_outbox_item"}) {
                assertThatThrownBy(() -> jdbc.update("delete from " + table))
                        .rootCause().hasMessageContaining("permission denied");
            }
        });
    }

    @Test
    void theCatalogueCoversEveryTypeAndLocksOnlyEscalation() {
        assertThat(NotificationCatalog.all()).extracting(NotificationCatalog.Entry::type)
                .containsExactly(NotificationType.values());
        assertThat(NotificationCatalog.all()).filteredOn(NotificationCatalog.Entry::locked)
                .extracting(NotificationCatalog.Entry::type).containsExactly(NotificationType.ESCALATION);
        assertThat(NotificationCatalog.optOut()).hasSize(14);
    }
}
