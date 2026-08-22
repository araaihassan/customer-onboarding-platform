package co.ara.onboarding.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * A Clock whose apparent time can be advanced without sleeping, for tests like
 * HoldTest that assert on elapsed business days. Wraps the system clock and
 * offsets it rather than freezing at a fixed instant, so ordinary "roughly now"
 * assertions elsewhere in the suite keep working unless a test explicitly calls
 * advance().
 *
 * reset() is called before every test (see PostgresTestBase) because this bean
 * is a Spring-managed singleton shared and cached across the whole suite -- an
 * advance() in one test would otherwise leak into every test that runs after it.
 */
public class MutableClock extends Clock {

    private volatile Clock delegate = Clock.systemUTC();

    public synchronized void advance(Duration duration) {
        delegate = Clock.offset(delegate, duration);
    }

    public synchronized void reset() {
        delegate = Clock.systemUTC();
    }

    @Override
    public ZoneId getZone() {
        return delegate.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return delegate.withZone(zone);
    }

    @Override
    public Instant instant() {
        return delegate.instant();
    }
}
