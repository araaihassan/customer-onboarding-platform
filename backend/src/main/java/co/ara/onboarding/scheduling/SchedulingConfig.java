package co.ara.onboarding.scheduling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Off under the test profile (tests invoke job bodies directly, spec 3.4) and switchable in any
 * other profile with {@code app.scheduling.enabled=false}.
 */
@Configuration
@EnableScheduling
@Profile("!test")
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);
    private final AuditPartitionJob partitions;

    SchedulingConfig(AuditPartitionJob partitions) { this.partitions = partitions; }

    /** A deployment that was down past a month boundary heals before the first cron tick. */
    @EventListener(ApplicationReadyEvent.class)
    void rollPartitionsAtStartup() {
        try {
            partitions.run();
        } catch (RuntimeException e) {
            log.error("Audit partition roll-forward failed at startup", e);
        }
    }
}
