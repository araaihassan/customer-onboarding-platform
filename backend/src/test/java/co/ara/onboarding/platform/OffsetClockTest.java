package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OffsetClockTest {

    @Test
    void aFreshClockTracksTheSystemClock() {
        OffsetClock c = new OffsetClock();
        assertThat(Duration.between(Instant.now(), c.instant()).abs()).isLessThan(Duration.ofSeconds(1));
        assertThat(c.offset()).isEqualTo(Duration.ZERO);
    }

    @Test
    void shiftMovesTheClockForwardAndAccumulates() {
        OffsetClock c = new OffsetClock();
        c.shift(Duration.ofDays(2));
        assertThat(Duration.between(Instant.now(), c.instant()))
                .isBetween(Duration.ofDays(2).minusSeconds(1), Duration.ofDays(2).plusSeconds(1));
        c.shift(Duration.ofDays(1));
        assertThat(c.offset()).isEqualTo(Duration.ofDays(3));
    }

    @Test
    void withZoneKeepsTheOffsetAndTheSameLiveBehaviour() {
        OffsetClock c = new OffsetClock();
        c.shift(Duration.ofDays(1));
        assertThat(Duration.between(Instant.now(), c.withZone(java.time.ZoneId.of("Asia/Riyadh")).instant()))
                .isGreaterThan(Duration.ofHours(23));
    }
}
