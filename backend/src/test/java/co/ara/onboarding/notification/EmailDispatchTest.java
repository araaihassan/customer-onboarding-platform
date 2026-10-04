package co.ara.onboarding.notification;

import co.ara.onboarding.scheduling.EmailDispatchJob;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static co.ara.onboarding.notification.FlakyEmail.failing;
import static org.assertj.core.api.Assertions.assertThat;

@Import(FlakyEmail.class)
class EmailDispatchTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired EmailDispatchJob dispatch;

    @AfterEach void reset() { FlakyEmail.reset(); }

    private UUID[] tenantWithUser(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, "u@" + slug + ".test"));
        return new UUID[]{t, u};
    }

    @Test
    void aQueuedEmailIsSentOnceWithAnAbsoluteLinkAndStamped() {
        UUID[] x = tenantWithUser("disp-ok");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-ok.test", "Hello"));
        assertThat(dispatch.runOne(x[0])).isEqualTo(1);
        var row = support.outbox(x[0]).get(0);
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(FlakyEmail.lastTo("u@disp-ok.test").body()).endsWith("Open: http://localhost:3000/t/x/path");
        assertThat(dispatch.runOne(x[0])).isZero();
        assertThat(FlakyEmail.recipients()).containsOnlyOnce("u@disp-ok.test");
    }

    @Test
    void failuresBackOffThenFailAfterFiveAttemptsAndAreAudited() {
        UUID[] x = tenantWithUser("disp-fail");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-fail.test", "Hello"));
        failing.set(true);
        Duration[] waits = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        dispatch.runOne(x[0]);
        for (Duration wait : waits) {
            var row = support.outbox(x[0]).get(0);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("last_error")).asString().contains("smtp down");
            assertThat(dispatch.runOne(x[0])).as("not due yet").isZero();
            clock.advance(wait.plusSeconds(1));
            dispatch.runOne(x[0]);
        }
        var row = support.outbox(x[0]).get(0);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(5);
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where tenant_id = ? and action = 'email.failed' and timeline_visible = false",
                Long.class, x[0])).isEqualTo(1L);
    }

    @Test
    void anInactiveRecipientIsSkippedNotSent() {
        UUID[] x = tenantWithUser("disp-inactive");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-inactive.test", "Hello"));
        ownerJdbc().update("update app_user set status = 'INACTIVE' where id = ?", x[1]);
        dispatch.runOne(x[0]);
        assertThat(support.outbox(x[0]).get(0).get("status")).isEqualTo("SKIPPED");
        assertThat(FlakyEmail.sent).isEmpty();
    }

    @Test
    void anExpiredLeaseIsReclaimed() {
        UUID[] x = tenantWithUser("disp-lease");
        fixture.runAs(x[0], () -> support.queueEmail(x[0], x[1], "u@disp-lease.test", "Hello"));
        // Simulate a dispatcher that claimed and then crashed before stamping.
        ownerJdbc().update("update email_outbox set status = 'SENDING', attempts = 1, lease_until = now() + interval '5 minutes' where tenant_id = ?", x[0]);
        assertThat(dispatch.runOne(x[0])).isZero();
        clock.advance(Duration.ofMinutes(6));
        assertThat(dispatch.runOne(x[0])).isEqualTo(1);
        assertThat(support.outbox(x[0]).get(0).get("attempts")).isEqualTo(2);
    }

    @Test
    void concurrentDispatchersNeverDoubleSend() throws Exception {   // Review Focus 4
        UUID[] x = tenantWithUser("disp-race");
        fixture.runAs(x[0], () -> {
            for (int i = 0; i < 20; i++) support.queueEmail(x[0], x[1], "u@disp-race.test", "Hello " + i);
        });
        FlakyEmail.hold = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> dispatch.runOne(x[0]));
            Future<Integer> b = pool.submit(() -> dispatch.runOne(x[0]));
            Thread.sleep(300);
            FlakyEmail.hold.countDown();
            assertThat(a.get(10, TimeUnit.SECONDS) + b.get(10, TimeUnit.SECONDS)).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        assertThat(FlakyEmail.sent).hasSize(20);
        assertThat(support.outbox(x[0])).allSatisfy(r -> assertThat(r.get("status")).isEqualTo("SENT"));
    }
}
