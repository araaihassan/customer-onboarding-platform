package co.ara.onboarding.scheduling;

import co.ara.onboarding.sla.SlaSweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Spec 3.4, 6.3: run 1 writes and commits; run 2 emails what run 1 committed (and retries failures). */
@Component
public class SlaSweepJob {

    private static final Logger log = LoggerFactory.getLogger(SlaSweepJob.class);

    private final TenantJobRunner runner;
    private final SlaSweepService sweep;

    public SlaSweepJob(TenantJobRunner runner, SlaSweepService sweep) {
        this.runner = runner;
        this.sweep = sweep;
    }

    @Scheduled(fixedDelayString = "${app.sla.sweep-interval:PT5M}", initialDelayString = "PT1M")
    public void scheduled() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("SLA sweep run failed", e);
        }
    }

    public List<UUID> runAll() {
        List<UUID> ran = runner.forEachTenant("sla-sweep", t -> sweep.sweep());
        // A separate run per tenant: email needs run 1's rows committed (retryUnsentEmail's precondition).
        runner.forEachTenant("sla-email", t -> sweep.retryUnsentEmail());
        return ran;
    }

    public boolean runOne(UUID tenantId) {
        boolean ran = runner.forTenant("sla-sweep", tenantId, t -> sweep.sweep());
        runner.forTenant("sla-email", tenantId, t -> sweep.retryUnsentEmail());
        return ran;
    }
}
