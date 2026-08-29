package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PasswordResetService recorded neither request nor completion before this
 * task -- one of the three write paths CLAUDE.md names as still unaudited.
 * Both events are compliance-only (timelineVisible = false), matching every
 * other identity/auth event; an unknown address must not be recorded at all,
 * since that would itself become an account-enumeration signal.
 */
class PasswordResetAuditTest extends PostgresTestBase {

    @Autowired PasswordResetService resets;
    @Autowired TenantFixture fixture;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void requestingAResetForAKnownAddressWritesATimelineInvisibleAuditEvent() {
        UUID tenant = fixture.createTenant("reset-audit-request");
        var user = fixture.createUserWithPassword(tenant, "audit-request@example.com", "the-old-password");

        fixture.runAs(tenant, () -> resets.request("audit-request@example.com"));

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("password_reset.requested:" + user.getId());

            assertThat(auditEvents.findAll())
                    .filteredOn(e -> "password_reset.requested".equals(e.getAction()))
                    .allMatch(e -> !e.isTimelineVisible());
        });
    }

    /**
     * The anti-enumeration guard: recording anything for an unknown address
     * would itself be a signal distinguishing "exists" from "does not exist".
     */
    @Test
    void requestingAResetForAnUnknownAddressWritesNoAuditEvent() {
        UUID tenant = fixture.createTenant("reset-audit-unknown");

        fixture.runAs(tenant, () -> resets.request("nobody@example.com"));

        fixture.runAs(tenant, () ->
                assertThat(auditEvents.findAll())
                        .filteredOn(e -> "password_reset.requested".equals(e.getAction()))
                        .isEmpty());
    }

    @Test
    void completingAResetWritesATimelineInvisibleAuditEvent() {
        UUID tenant = fixture.createTenant("reset-audit-complete");
        var user = fixture.createUserWithPassword(tenant, "audit-complete@example.com", "the-old-password");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> token.set(resets.request("audit-complete@example.com").orElseThrow()));
        fixture.runAs(tenant, () -> resets.reset(token.get(), "a-brand-new-password"));

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("password_reset.completed:" + user.getId());

            assertThat(auditEvents.findAll())
                    .filteredOn(e -> "password_reset.completed".equals(e.getAction()))
                    .allMatch(e -> !e.isTimelineVisible());
        });
    }
}
