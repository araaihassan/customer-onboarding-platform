package co.ara.onboarding.scheduling;

import co.ara.onboarding.auth.EmailMessage;
import co.ara.onboarding.auth.EmailSender;
import co.ara.onboarding.notification.EmailDispatchService;
import co.ara.onboarding.notification.EmailDispatchService.Claimed;
import co.ara.onboarding.notification.EmailDispatchService.Outcome;
import co.ara.onboarding.notification.EmailDispatchService.Result;
import co.ara.onboarding.platform.PublicBaseUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spec §6.4: claim (locked tenant run) -> send (no transaction) -> stamp (unlocked tenant run).
 * A crash between send and stamp leaves the lease to expire and the row is sent again:
 * at-least-once, one duplicate at most per crash. A batch stops starting sends once
 * {@link EmailDispatchService#SEND_BUDGET} has passed since its claim and releases the rest, so a slow
 * SMTP server can never outrun the lease and let a second dispatcher re-claim a row still being sent.
 */
@Component
public class EmailDispatchJob {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchJob.class);
    static final int BATCH = 50;
    static final int MAX_BATCHES = 10;

    private final TenantJobRunner runner;
    private final EmailDispatchService dispatch;
    private final EmailSender email;
    private final PublicBaseUrl baseUrl;
    private final Clock clock;

    public EmailDispatchJob(TenantJobRunner runner, EmailDispatchService dispatch, EmailSender email,
                            PublicBaseUrl baseUrl, Clock clock) {
        this.runner = runner;
        this.dispatch = dispatch;
        this.email = email;
        this.baseUrl = baseUrl;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.notifications.dispatch-interval:PT1M}", initialDelayString = "PT30S")
    public void scheduled() {
        try {
            runAll();
        } catch (RuntimeException e) {
            log.error("Email dispatch run failed", e);
        }
    }

    public void runAll() {
        for (UUID tenant : runner.activeTenantIds()) {
            try {
                runOne(tenant);
            } catch (RuntimeException e) {
                log.error("Email dispatch failed for tenant {}", tenant, e);
            }
        }
    }

    /** Returns how many emails were sent. */
    public int runOne(UUID tenantId) {
        int sent = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            List<Claimed> claimed = new ArrayList<>();
            Instant claimedAt = clock.instant();            // before the claim, so never later than the lease start
            runner.forTenant("email-claim", tenantId, t -> claimed.addAll(dispatch.claim(BATCH)));
            if (claimed.isEmpty()) break;
            List<Result> results = new ArrayList<>();
            boolean outOfTime = false;
            for (Claimed c : claimed) {
                outOfTime = outOfTime
                        || Duration.between(claimedAt, clock.instant()).compareTo(EmailDispatchService.SEND_BUDGET) >= 0;
                results.add(outOfTime ? new Result(c.id(), Outcome.RELEASED, null) : send(c));
            }
            runner.forTenantUnlocked("email-stamp", tenantId, t -> dispatch.stamp(results));
            sent += (int) results.stream().filter(r -> r.outcome() == Outcome.SENT).count();
            if (outOfTime) {
                log.warn("Email dispatch for tenant {} released {} unsent row(s): the batch outran its send budget",
                        tenantId, results.stream().filter(r -> r.outcome() == Outcome.RELEASED).count());
                break;
            }
            if (claimed.size() < BATCH) break;
        }
        return sent;
    }

    private Result send(Claimed c) {
        String body = c.linkPath() == null ? c.body() : c.body() + "\n\nOpen: " + baseUrl.absolute(c.linkPath());
        try {
            email.send(new EmailMessage(c.to(), c.subject(), body));
            return new Result(c.id(), Outcome.SENT, null);
        } catch (RuntimeException e) {
            log.warn("Email {} ({}) failed on attempt {}", c.id(), c.kind(), c.attempts(), e);
            return new Result(c.id(), Outcome.FAILED, e.getMessage());
        }
    }
}
