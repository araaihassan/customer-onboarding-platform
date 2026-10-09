package co.ara.onboarding.scheduling;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.OffsetClock;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

/**
 * Dev-profile-only test levers (spec 10.3). The swept tenant is always the caller's own, taken from
 * TenantContext (resolved from the path slug and checked ACTIVE by TenantContextFilter) -- never
 * from a request value. The clock offset is process-wide: it moves "now" for every tenant in this
 * dev process.
 */
@Service
@Profile("dev")
public class DevToolsService {

    private static final long MAX_SHIFT_SECONDS = Duration.ofDays(60).toSeconds();

    private final OffsetClock clock;
    private final SlaSweepJob sweepJob;
    private final NotificationSweepJob notificationSweep;
    private final DigestJob digests;
    private final EmailDispatchJob dispatch;

    public DevToolsService(OffsetClock clock, SlaSweepJob sweepJob, NotificationSweepJob notificationSweep,
                           DigestJob digests, EmailDispatchJob dispatch) {
        this.clock = clock;
        this.sweepJob = sweepJob;
        this.notificationSweep = notificationSweep;
        this.digests = digests;
        this.dispatch = dispatch;
    }

    // @Transactional(readOnly) on both: the permission gate runs inside the tenant binder, which only
    // exists within a transaction; without one the gate reads zero grants (RLS) and 403s an administrator.
    @Transactional(readOnly = true)
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public long shiftClock(long seconds) {
        if (seconds <= 0 || seconds > MAX_SHIFT_SECONDS) {
            throw new IllegalArgumentException("seconds must be between 1 and 60 days");
        }
        clock.shift(Duration.ofSeconds(seconds));
        return clock.offset().toSeconds();
    }

    /** The outer transaction only carries the gate; TenantJobRunner runs the sweep in its own REQUIRES_NEW transactions. */
    @Transactional(readOnly = true)
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public boolean runSweep() {
        return sweepJob.runOne(TenantContext.getRequired());
    }

    @Transactional(readOnly = true)
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public boolean runNotificationSweep() {
        return notificationSweep.runOne(TenantContext.getRequired());
    }

    /** Returns how many digests were queued. */
    @Transactional(readOnly = true)
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public int runDigest() {
        return digests.runOne(TenantContext.getRequired());
    }

    /** Returns how many emails were sent. */
    @Transactional(readOnly = true)
    @RequirePermission(PermissionKeys.TENANT_SETTINGS_EDIT)
    public int runEmailDispatch() {
        return dispatch.runOne(TenantContext.getRequired());
    }
}
