package co.ara.onboarding.sla;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.config.BeanPostProcessor;
import co.ara.onboarding.support.RecordingEmailSender;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** A failing send neither fails the sweep nor is lost: it is retried, and only to who has not been reached. */
@Import(EscalationRetryTest.Flaky.class)
class EscalationRetryTest extends PostgresTestBase {

    static final AtomicBoolean failingFor = new AtomicBoolean();
    static final List<String> delivered = new CopyOnWriteArrayList<>();
    static volatile String failingAddress = "";

    @TestConfiguration
    static class Flaky {
        /** Wraps the shared @Primary RecordingEmailSender, so no second @Primary bean competes with it. */
        @Bean static BeanPostProcessor flakySender() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof RecordingEmailSender)) return bean;
                    return (EmailSender) (EmailMessage m) -> {
                        if (failingFor.get() && m.to().equals(failingAddress)) throw new IllegalStateException("smtp down");
                        delivered.add(m.to());
                    };
                }
            };
        }
    }

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired BusinessCalendar calendar;
    @Autowired co.ara.onboarding.support.MutableClock clock;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        failingFor.set(false);
    }

    private long unsent(UUID t) {
        return ownerJdbc().queryForObject(
                "select count(*) from email_outbox where tenant_id = ? and status <> 'SENT'", Long.class, t);
    }

    @Test
    void aFailedSendIsRetriedAfterBackoffAndOnlyToWhoWasNotReached() {
        UUID t = fixture.createTenant("retry");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID late = fixture.runAsReturning(t, () -> fixture.createUser(t, "late@retry.test"));
        UUID task = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, late, null)).id());
        ownerJdbc().update("update task set due_date = ? where id = ?",
                fixture.runAsReturning(t, () -> calendar.today()).minusDays(7), task);
        fixture.createAdminUser(t, "ok@retry.test");
        fixture.createAdminUser(t, "down@retry.test");
        ownerJdbc().update("update role set name = 'Administrator' where id = ?", fixture.administratorRoleId(t));
        failingAddress = "down@retry.test";
        failingFor.set(true);

        sla.sweepAndEmail(t);   // must not throw
        assertThat(sla.escalationCount(t)).isEqualTo(1);
        assertThat(unsent(t)).isEqualTo(1L);
        assertThat(delivered).contains("ok@retry.test").doesNotContain("down@retry.test");

        failingFor.set(false);
        clock.advance(java.time.Duration.ofMinutes(2));   // the first retry is due one minute after the failure
        sla.sweepAndEmail(t);
        assertThat(unsent(t)).isZero();
        assertThat(delivered).containsOnlyOnce("down@retry.test").containsOnlyOnce("ok@retry.test");
    }
}
