package co.ara.onboarding.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/**
 * Platform-level beans that cross all modules. Clock is injectable across
 * the platform for time-dependent logic in tests (advancing a fake clock)
 * and for production execution.
 */
@Configuration
public class PlatformBeansConfig {

    @Bean
    @Profile("!dev")
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Dev only: a shiftable clock for end-to-end time travel (spec 10.3). Bean name stays "clock". */
    @Bean(name = "clock")
    @Profile("dev")
    public OffsetClock devClock() {
        return new OffsetClock();
    }
}
