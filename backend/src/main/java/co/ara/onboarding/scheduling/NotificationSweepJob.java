package co.ara.onboarding.scheduling;

import co.ara.onboarding.notification.NotificationSweepService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Spec 6.2: the periodic deadline sweep, per tenant, as the system principal. Delivery is the outbox dispatcher's. */
@Component
public class NotificationSweepJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationSweepJob.class);

    private final TenantJobRunner runner;
    private final NotificationSweepService sweep;
    private final EmailDispatchJob dispatch;

    public NotificationSweepJob(TenantJobRunner runner, NotificationSweepService sweep, EmailDispatchJob dispatch) {
        this.runner = runner;
        this.sweep = sweep;
        this.dispatch = dispatch;
    }

    @Scheduled(fixedDelayString = "${app.notifications.sweep-interval:PT1H}", initialDelayString = "PT2M")
    public void scheduled() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("Notification sweep failed", e);
        }
    }

    public List<UUID> runAll() {
        return runner.forEachTenant("notification-sweep", t -> sweep.sweep());
    }

    /** Sweeps one tenant, then dispatches its email (dev endpoint, tests). */
    public boolean runOne(UUID tenantId) {
        boolean ran = runner.forTenant("notification-sweep", tenantId, t -> sweep.sweep());
        dispatch.runOne(tenantId);
        return ran;
    }
}
