package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class AuditRecorderTest extends PostgresTestBase {

    @Autowired AuditRecorder recorder;
    @Autowired AuditEventRepository events;
    @Autowired TenantFixture fixture;

    @Test
    void recordsEventWithTenantAndAction() {
        UUID tenant = fixture.createTenant("audit-co");
        UUID resourceId = UUID.randomUUID();

        fixture.runAs(tenant, () -> recorder.record(
                AuditActions.CUSTOMER_CREATED, "customer", resourceId,
                "Created customer Acme", Map.of("displayName", "Acme")));

        fixture.runAs(tenant, () -> {
            var all = events.findAll();
            assertThat(all).hasSize(1);
            var e = all.get(0);
            assertThat(e.getTenantId()).isEqualTo(tenant);
            assertThat(e.getAction()).isEqualTo("customer.created");
            assertThat(e.getResourceId()).isEqualTo(resourceId);
            assertThat(e.isTimelineVisible()).isTrue();
        });
    }

    @Test
    void rejectsActionOutsideRegistry() {
        assertThat(AuditActions.byKey("not.a.real.action")).isEmpty();
    }

    // Without this, both tests above would still pass if BY_KEY were left
    // empty by a future refactor: recordsEventWithTenantAndAction uses the
    // CUSTOMER_CREATED constant directly, never byKey, and
    // rejectsActionOutsideRegistry only exercises the not-found case. This is
    // exactly the static-init-order bug fixed earlier in this task, now
    // covered.
    @Test
    void byKeyReturnsSeededAction() {
        assertThat(AuditActions.byKey("customer.created"))
                .contains(AuditActions.CUSTOMER_CREATED);
    }
}
