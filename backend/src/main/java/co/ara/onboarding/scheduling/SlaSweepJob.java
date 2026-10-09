package co.ara.onboarding.scheduling;

import co.ara.onboarding.sla.SlaSweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Run 1 writes and commits; delivery is the outbox dispatcher's (6B spec 6.4) -- runOne also
 * dispatches, for the dev endpoint.
 */
@Component
public class SlaSweepJob {

    private static final Logger log = LoggerFactory.getLogger(SlaSweepJob.class);

    private final TenantJobRunner runner;
    private final SlaSweepService sweep;
    private final EmailDispatchJob dispatch;

    public SlaSweepJob(TenantJobRunner runner, SlaSweepService sweep, EmailDispatchJob dispatch) {
        this.dispatch = dispatch;
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
        return runner.forEachTenant("sla-sweep", t -> sweep.sweep());
    }

    public boolean runOne(UUID tenantId) {
        boolean ran = runner.forTenant("sla-sweep", tenantId, t -> sweep.sweep());
        dispatch.runOne(tenantId);
        return ran;
    }
}
