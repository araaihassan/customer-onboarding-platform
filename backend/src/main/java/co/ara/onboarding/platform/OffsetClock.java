package co.ara.onboarding.platform;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A system clock that a developer can shift forward, registered as the application Clock under the
 * dev profile only (spec 10.3). Playwright uses it to cross a business day. Never present in any
 * other profile -- DevToolsProfileTest proves the test profile does not get it.
 *
 * <p>The offset is process-wide: it moves "now" for every tenant in the dev process. It never
 * affects the database's own now().
 */
public class OffsetClock extends Clock {
    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);
    private final ZoneId zone;

    public OffsetClock() { this(ZoneOffset.UTC); }
    private OffsetClock(ZoneId zone) { this.zone = zone; }

    public void shift(Duration d) { offset.updateAndGet(o -> o.plus(d)); }
    public Duration offset() { return offset.get(); }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return Clock.offset(Clock.system(z), offset.get()); }
    @Override public Instant instant() { return Instant.now().plus(offset.get()); }
}
