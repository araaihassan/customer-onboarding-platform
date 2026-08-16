package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class Uuid7Test {

    @Test
    void hasVersionSevenAndRfc4122Variant() {
        UUID id = Uuid7.generate();
        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void idsGeneratedInSequenceSortAscending() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5000; i++) ids.add(Uuid7.generate());

        List<UUID> sorted = new ArrayList<>(ids);
        sorted.sort(Uuid7::compareUnsigned);
        assertThat(sorted)
            .as("time-ordered keys must be monotonic even within the same millisecond")
            .isEqualTo(ids);
    }

    @Test
    void encodesCurrentTimeInTheLeading48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Uuid7.generate();
        long after = System.currentTimeMillis();

        long timestamp = id.getMostSignificantBits() >>> 16;
        // Both bounds matter: isBetween's upper bound (after) is what proves
        // the encoded timestamp is never ahead of the wall clock at the
        // moment of generation -- the property the unbounded borrow-forward
        // in the old nextStamp() could violate.
        assertThat(timestamp).isBetween(before, after);
    }

    @Test
    void neverEncodesATimestampAheadOfTheClockUnderABurst() {
        // 5,000 > the 4,096-per-millisecond counter capacity, so this
        // saturates the counter at least once and forces nextStamp() to
        // either borrow forward (the old, buggy behaviour) or wait for the
        // clock (the fix). Checking the max AFTER the whole burst, against a
        // single System.currentTimeMillis() read taken once the burst is
        // over, is what makes this a meaningful assertion: any timestamp
        // encoded ahead of real time during the burst would still exceed
        // "now" here.
        long maxTimestamp = 0;
        for (int i = 0; i < 5000; i++) {
            UUID id = Uuid7.generate();
            long timestamp = id.getMostSignificantBits() >>> 16;
            maxTimestamp = Math.max(maxTimestamp, timestamp);
        }
        long now = System.currentTimeMillis();

        assertThat(maxTimestamp).isLessThanOrEqualTo(now);
    }

    @Test
    void generatesDistinctValuesUnderContention() throws Exception {
        var ids = java.util.Collections.synchronizedSet(new java.util.HashSet<UUID>());
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 8; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 2000; i++) ids.add(Uuid7.generate());
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join();
        assertThat(ids).hasSize(8 * 2000);
    }
}
