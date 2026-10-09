package co.ara.onboarding.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Final-review Important 3, proven against the beans Spring Boot actually builds from the shipped
 * application.yml (not just the property strings): the SMTP client gives up instead of waiting
 * forever, and a slow dispatcher cannot occupy the only scheduler thread the SLA sweep needs.
 */
class MailAndSchedulerConfigTest {

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.mail.host=smtp.example.test")
            .withUserConfiguration(Scheduling.class)
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class, TaskSchedulingAutoConfiguration.class));

    @Test
    void theSmtpClientHasConnectReadAndWriteTimeouts() {
        runner.run(context -> {
            var props = context.getBean(JavaMailSenderImpl.class).getJavaMailProperties();
            assertThat(props.getProperty("mail.smtp.connectiontimeout")).isEqualTo("10000");
            assertThat(props.getProperty("mail.smtp.timeout")).isEqualTo("30000");
            assertThat(props.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
        });
    }

    @Test
    void theSchedulerHasEnoughThreadsThatADispatchRunCannotStallTheSweeps() {
        runner.run(context -> assertThat(context.getBean(ThreadPoolTaskScheduler.class)
                .getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4));
    }
}
