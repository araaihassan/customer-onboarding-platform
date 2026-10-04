package co.ara.onboarding.notification;

import co.ara.onboarding.document.DocumentRequestService;
import co.ara.onboarding.scheduling.EmailDispatchJob;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.Duration;
import static co.ara.onboarding.notification.FlakyEmail.failing;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(FlakyEmail.class)
class CustomerReminderOutboxTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired EmailDispatchJob dispatch;
    @Autowired DocumentRequestService requests;

    @AfterEach void reset() { FlakyEmail.reset(); }

    @Test
    void aReminderIsQueuedNotSentInline() {
        var x = support.openRequestWithContact("rem-queued");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        var row = support.outbox(x.tenant()).get(0);
        assertThat(row.get("kind")).isEqualTo("CUSTOMER_REMINDER");
        assertThat(row.get("contact_id")).isEqualTo(x.contactId());
        assertThat(row.get("recipient_user_id")).isNull();
        assertThat(row.get("document_request_id")).isEqualTo(x.requestId());
        assertThat(FlakyEmail.sent).isEmpty();               // nothing sent inside the request
        dispatch.runOne(x.tenant());
        assertThat(FlakyEmail.recipients()).containsExactly(x.contactEmail());
    }

    @Test
    void aFailedReminderEmailIsRetriedNotLost() {
        var x = support.openRequestWithContact("rem-retry");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        failing.set(true);
        dispatch.runOne(x.tenant());
        assertThat(support.outbox(x.tenant()).get(0).get("status")).isEqualTo("PENDING");
        failing.set(false);
        clock.advance(Duration.ofMinutes(2));
        dispatch.runOne(x.tenant());
        assertThat(FlakyEmail.recipients()).containsExactly(x.contactEmail());
    }

    @Test
    void aRolledBackReminderQueuesNothing() {
        var x = support.openRequestWithContact("rem-rollback");
        fixture.runAs(x.tenant(), () -> requests.remind(x.requestId()));
        // The 24h floor refuses a second reminder: its transaction rolls back, so no second row.
        assertThatThrownBy(() -> fixture.runAs(x.tenant(), () -> requests.remind(x.requestId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(support.outbox(x.tenant())).hasSize(1);
    }
}
