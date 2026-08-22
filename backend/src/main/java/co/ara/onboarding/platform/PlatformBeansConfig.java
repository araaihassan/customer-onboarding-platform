package co.ara.onboarding.platform;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Platform-level beans that cross all modules. Clock is injectable across
 * the platform for time-dependent logic in tests (advancing a fake clock)
 * and for production execution.
 */
@Configuration
public class PlatformBeansConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
