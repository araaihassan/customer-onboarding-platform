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
}
