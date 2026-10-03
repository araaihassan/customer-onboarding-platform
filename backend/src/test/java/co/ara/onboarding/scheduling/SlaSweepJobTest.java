package co.ara.onboarding.scheduling;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlaSweepJobTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired BusinessCalendar calendar;
    @Autowired SlaSweepJob job;

    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private UUID overdue(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID late = fixture.runAsReturning(t, () -> fixture.createUser(t, "late@" + slug + ".test"));
        UUID task = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, late, null)).id());
        ownerJdbc().update("update task set due_date = ? where id = ?",
                fixture.runAsReturning(t, () -> calendar.today()).minusDays(7), task);
        fixture.createAdminUser(t, "admin2@" + slug + ".test");
        ownerJdbc().update("update role set name = 'Administrator' where id = ?", fixture.administratorRoleId(t));
        return t;
    }

    @Test
    void runAllSweepsEveryActiveTenantAndEmails() {
        UUID a = overdue("job-sweep-a");
        UUID b = overdue("job-sweep-b");
        assertThat(job.runAll()).contains(a, b);
        for (UUID t : new UUID[] {a, b}) {
            assertThat(sla.escalationCount(t)).isEqualTo(1);
            assertThat(ownerJdbc().queryForObject(
                    "select count(*) from notification where tenant_id = ? and emailed_at is not null",
                    Integer.class, t)).isPositive();
        }
    }

    @Test
    void runOneSweepsOnlyThatTenant() {
        UUID a = overdue("job-one-a");
        UUID b = overdue("job-one-b");
        assertThat(job.runOne(a)).isTrue();
        assertThat(sla.escalationCount(a)).isEqualTo(1);
        assertThat(sla.escalationCount(b)).isZero();
    }
}
