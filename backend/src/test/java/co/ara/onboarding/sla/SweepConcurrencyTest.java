package co.ara.onboarding.sla;

import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.scheduling.TenantJobRunner;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Invariant 5 under a real race: the database key, not the advisory lock, must hold. */
class SweepConcurrencyTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired TaskService tasks;
    @Autowired SlaSweepService sweep;
    @Autowired TenantJobRunner runner;
    @Autowired BusinessCalendar calendar;

    @Test
    void concurrentSweepsEscalateOnce() throws Exception {
        UUID t = fixture.createTenant("sweep-race");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID task = fixture.runAsReturning(t, () -> tasks.create(caseId, new CreateTaskRequest(
                sla.milestoneIdAt(caseId, 0), null, "Chase", null, TaskPriority.MEDIUM, null, null)).id());
        var today = fixture.runAsReturning(t, () -> calendar.today());
        ownerJdbc().update("update task set due_date = ? where id = ?", today.minusDays(7), task);

        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var futures = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                String job = "sla-race-" + i;   // different jobs: the advisory lock must not serialise them
                futures.add(pool.submit(() -> runner.forTenant(job, t, x -> {
                    try { barrier.await(); } catch (Exception e) { throw new RuntimeException(e); }
                    sweep.escalateOverdue();
                })));
            }
            for (var f : futures) assertThat(f.get(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(ownerJdbc().queryForObject("select count(*) from escalation where subject_id = ?", Long.class, task))
                .isEqualTo(1L);
        assertThat(ownerJdbc().queryForObject(
                "select count(*) from audit_event where action = 'escalation.raised' and tenant_id = ?",
                Long.class, t)).isEqualTo(1L);
    }
}
