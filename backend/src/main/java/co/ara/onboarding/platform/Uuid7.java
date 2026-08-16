package co.ara.onboarding.platform;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RFC 9562 version 7 UUIDs: a 48-bit millisecond timestamp followed by
 * randomness, so primary keys sort by creation time and index inserts stay
 * local instead of scattering across the B-tree.
 *
 * These IDs deliberately leak creation time. That is acceptable for entity
 * identifiers; anything that must be unpredictable (refresh tokens,
 * invitation tokens) uses SecureRandom bytes directly and never a UUID.
 *
 * The encoded timestamp never runs ahead of {@code System.currentTimeMillis()}:
 * when the per-millisecond counter is exhausted, generation spin-waits for the
 * clock to advance rather than borrowing time forward without bound. See
 * {@link #nextStamp()}.
 *
 * One exception, and it is strictly better than always borrowing forward: if
 * the system clock moves backwards (e.g. an NTP correction), monotonicity is
 * preserved by continuing to increment the counter from the last observed
 * stamp, which does encode a timestamp ahead of the now-earlier clock, bounded
 * by the size of the backward jump. This is intentional -- ordering survives a
 * clock rewind -- and self-corrects the moment the clock catches back up.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Packs the last timestamp and counter into one long for lock-free CAS. */
    private static final AtomicLong LAST = new AtomicLong();

    private static final int COUNTER_BITS = 12;
    private static final long COUNTER_MASK = (1L << COUNTER_BITS) - 1;

    private Uuid7() {}

    public static UUID generate() {
        long stamp = nextStamp();
        long millis = stamp >>> COUNTER_BITS;
        long counter = stamp & COUNTER_MASK;

        // 48 bits timestamp | 4 bits version (7) | 12 bits monotonic counter
        long msb = (millis << 16) | (0x7L << 12) | counter;

        // 2 bits variant (RFC 4122) | 62 bits randomness
        long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;

        return new UUID(msb, lsb);
    }

    /**
     * Returns a strictly increasing (millis, counter) pair.
     *
     * On a fresh millisecond, the counter resets. Within the same millisecond,
     * the counter advances. If the counter is exhausted (4096 ids already
     * issued this millisecond), this spin-waits for the clock to advance
     * rather than carrying into the millis field -- that carry is what
     * previously let the encoded timestamp drift arbitrarily far ahead of
     * {@code System.currentTimeMillis()} under a sustained burst, since
     * nothing ever capped it. The only case that still advances the millis
     * field without the clock agreeing is a clock that has gone backwards
     * relative to the last observed stamp (see the class javadoc).
     */
    private static long nextStamp() {
        while (true) {
            long previous = LAST.get();
            long now = System.currentTimeMillis();
            long previousMillis = previous >>> COUNTER_BITS;

            long candidate;
            if (now > previousMillis) {
                candidate = now << COUNTER_BITS;                 // fresh millisecond
            } else if ((previous & COUNTER_MASK) != COUNTER_MASK) {
                candidate = previous + 1;                        // same millisecond, counter available
            } else {
                // Counter exhausted for this millisecond. Wait for the clock rather
                // than borrowing time forward without bound.
                Thread.onSpinWait();
                continue;
            }

            if (LAST.compareAndSet(previous, candidate)) return candidate;
        }
    }

    /** UUIDs compare signed by default, which breaks ordering above 0x7F. */
    public static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
