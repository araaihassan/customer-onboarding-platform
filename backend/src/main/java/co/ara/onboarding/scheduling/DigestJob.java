package co.ara.onboarding.scheduling;

import co.ara.onboarding.notification.DigestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Spec 6.3: the periodic digest run, per tenant, as the system principal. Delivery is the outbox dispatcher's. */
@Component
public class DigestJob {

    private static final Logger log = LoggerFactory.getLogger(DigestJob.class);

    private final TenantJobRunner runner;
    private final DigestService digests;
    private final EmailDispatchJob dispatch;

    public DigestJob(TenantJobRunner runner, DigestService digests, EmailDispatchJob dispatch) {
        this.runner = runner;
        this.digests = digests;
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${app.notifications.digest-interval:PT15M}", initialDelayString = "PT3M")
    public void scheduled() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("Digest run failed", e);
        }
    }

    public List<UUID> runAll() {
        return runner.forEachTenant("notification-digest", t -> digests.run());
    }

    /** Runs one tenant's digests, then dispatches its email (tests, dev). Returns how many digests were queued. */
    public int runOne(UUID tenantId) {
        int[] queued = new int[1];
        runner.forTenant("notification-digest", tenantId, t -> queued[0] = digests.run());
        dispatch.runOne(tenantId);
        return queued[0];
    }
}
