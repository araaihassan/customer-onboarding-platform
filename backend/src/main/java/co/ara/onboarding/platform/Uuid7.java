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
     * Returns a strictly increasing (millis, counter) pair. Within a single
     * millisecond the counter advances; if it saturates, the timestamp is
     * borrowed forward so ordering is never violated.
     */
    private static long nextStamp() {
        while (true) {
            long previous = LAST.get();
            long now = System.currentTimeMillis();
            long candidate = (now << COUNTER_BITS);
            long next = (candidate > previous) ? candidate : previous + 1;
            if (LAST.compareAndSet(previous, next)) return next;
        }
    }

    /** UUIDs compare signed by default, which breaks ordering above 0x7F. */
    public static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high
                : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
