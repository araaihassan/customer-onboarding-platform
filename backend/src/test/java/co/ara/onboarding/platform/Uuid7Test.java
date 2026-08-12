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
        assertThat(timestamp).isBetween(before, after);
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
