package co.ara.onboarding.scheduling;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigTest extends PostgresTestBase {

    @Autowired ApplicationContext context;

    @Test
    void schedulingIsOffUnderTheTestProfile() {
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeansOfType(SchedulingConfig.class)).isEmpty();
    }

    @Test
    void theSchedulerHasFourThreadsSoNeitherALongSweepNorASlowDispatchCanStallTheOthers() {
        assertThat(context.getEnvironment().getProperty("spring.task.scheduling.pool.size")).isEqualTo("4");
    }
}
