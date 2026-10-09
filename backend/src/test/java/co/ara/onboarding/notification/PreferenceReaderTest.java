package co.ara.onboarding.notification;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreferenceReaderTest extends PostgresTestBase {
    @Autowired TenantFixture fixture;
    @Autowired PreferenceReader prefs;

    @Test
    void withNoRowsEveryTypeUsesItsCatalogueDefaultAndCadenceIsImmediate() {
        UUID t = fixture.createTenant("pref-defaults");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-defaults.test"));
        fixture.runAs(t, () -> {
            for (var e : NotificationCatalog.all()) {
                var r = prefs.resolve(u, e.type());
                assertThat(r.inApp()).as(e.type().name()).isEqualTo(e.inAppDefault());
                assertThat(r.email()).as(e.type().name()).isEqualTo(e.emailDefault());
                assertThat(r.cadence()).isEqualTo(EmailCadence.IMMEDIATE);
            }
        });
    }

    @Test
    void aRowOverridesTheDefaultAndSettingsSetTheCadence() {
        UUID t = fixture.createTenant("pref-override");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-override.test"));
        ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'TASK_ASSIGNED', false, true, now(), now())", t, u);
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'DAILY', now(), now())", t, u);
        fixture.runAs(t, () -> {
            var r = prefs.resolve(u, NotificationType.TASK_ASSIGNED);
            assertThat(r.inApp()).isFalse();
            assertThat(r.email()).isTrue();
            assertThat(r.cadence()).isEqualTo(EmailCadence.DAILY);
        });
    }

    @Test
    void escalationIsAlwaysOnAndImmediate() {
        UUID t = fixture.createTenant("pref-esc");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-esc.test"));
        ownerJdbc().update("insert into notification_settings (id, tenant_id, user_id, email_cadence, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'WEEKLY', now(), now())", t, u);
        fixture.runAs(t, () -> assertThat(prefs.resolve(u, NotificationType.ESCALATION))
                .isEqualTo(new PreferenceReader.Resolved(true, true, EmailCadence.IMMEDIATE)));
    }

    @Test
    void escalationCannotBeStoredAsAPreferenceRow() {
        UUID t = fixture.createTenant("pref-esc-row");
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@pref-esc-row.test"));
        assertThatThrownBy(() -> ownerJdbc().update("insert into notification_preference (id, tenant_id, user_id, type, in_app_enabled, email_enabled, created_at, updated_at) values (gen_random_uuid(), ?, ?, 'ESCALATION', false, false, now(), now())", t, u))
                .hasMessageContaining("notification_preference_type_check");
    }
}
