package co.ara.onboarding.audit;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // A non-serializable payload is a programming error in whatever service
    // built it, not bad client input -- it must NOT surface as the same
    // IllegalArgumentException platform.ApiExceptionHandler maps to a 400
    // (see task-24-review-1.md's Important finding). Asserting the thrown
    // type here is what actually proves that; per CLAUDE.md, the assertion
    // wraps the runAs call rather than living inside its lambda.
    @Test
    void nonSerializablePayloadThrowsAuditSerializationExceptionNotIllegalArgument() {
        UUID tenant = fixture.createTenant("audit-bad-payload");
        UUID resourceId = UUID.randomUUID();

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> recorder.record(
                AuditActions.CUSTOMER_CREATED, "customer", resourceId,
                "Created customer Acme", new Unserializable())))
                .isInstanceOf(AuditSerializationException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessage("Audit payload is not serializable");
    }

    /** A getter that always throws, so Jackson fails to serialize it regardless of ObjectMapper config. */
    static final class Unserializable {
        public String getValue() {
            throw new RuntimeException("not actually serializable");
        }
    }
}
