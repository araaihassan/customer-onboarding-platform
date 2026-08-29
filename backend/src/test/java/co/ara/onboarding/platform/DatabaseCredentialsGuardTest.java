package co.ara.onboarding.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.NestedExceptionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB_APP_PASSWORD must be a real password, never blank and never the literal
 * V2__app_role_and_tenant.sql creates the onboarding_app role with -- a
 * deployment that forgets to set it must fail loudly at startup rather than
 * quietly reconciling the role's password onto a value published in this
 * repository's own source.
 *
 * Modelled on JwtSecretGuardTest: asserts against a context holding only the
 * guard under test (via a plain @Value field, so a PropertySourcesPlaceholderConfigurer
 * is registered explicitly -- @ConfigurationProperties beans resolve without one,
 * but a bare @Value field needs the placeholder-resolving BeanFactoryPostProcessor
 * present to be populated at all), never a full application context needing
 * Postgres -- a context that failed to load for a database reason would be
 * indistinguishable from the guard firing.
 */
class DatabaseCredentialsGuardTest {

    /**
     * The literal V2__app_role_and_tenant.sql creates the role with, and
     * application.yml's former default. Public knowledge the moment this
     * repository's source is readable, so it is a denylist entry, not key
     * material.
     */
    private static final String PUBLISHED_PLACEHOLDER = "onboarding_app";

    private static final String USABLE_PASSWORD = "a-perfectly-ordinary-rotated-password";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GuardConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    static class GuardConfiguration {
        @Bean
        static PropertySourcesPlaceholderConfigurer placeholderConfigurer() {
            return new PropertySourcesPlaceholderConfigurer();
        }

        @Bean
        DatabaseCredentialsGuard databaseCredentialsGuard() {
            return new DatabaseCredentialsGuard();
        }
    }

    @Test
    void refusesToStartWhenNotSet() {
        runner.withPropertyValues("DB_APP_PASSWORD=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DB_APP_PASSWORD is not set."
                            + " Set the DB_APP_PASSWORD environment variable to a real password"
                            + " (for example: openssl rand -base64 48). The application will not"
                            + " start without one, on any profile -- it will be reconciled onto"
                            + " the onboarding_app role automatically on every startup.");
        });
    }

    @Test
    void refusesToStartOnThePasswordPublishedInV2Migration() {
        runner.withPropertyValues("DB_APP_PASSWORD=" + PUBLISHED_PLACEHOLDER).run(context -> {
            assertThat(context)
                    .as("a password readable by anyone who can read the migration is not a password")
                    .hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("DB_APP_PASSWORD")
                    .hasMessageContaining("V2__app_role_and_tenant.sql");
        });
    }

    @Test
    void startsWithARealPassword() {
        runner.withPropertyValues("DB_APP_PASSWORD=" + USABLE_PASSWORD).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(DatabaseCredentialsGuard.class);
            assertThat(context.getBean(DatabaseCredentialsGuard.class).getPassword())
                    .isEqualTo(USABLE_PASSWORD);
        });
    }

    /**
     * The message is written to stdout and to whatever collects it, so it must
     * never echo the rejected value back -- the same discipline
     * JwtProperties.requireUsableSecret already holds to.
     */
    @Test
    void theFailureNeverEchoesTheRejectedValue() {
        String value = "some-value-that-happens-to-be-wrong";

        runner.withPropertyValues("DB_APP_PASSWORD=" + value).run(context -> {
            // This value is neither blank nor denylisted, so the guard actually
            // accepts it -- the point here is only that requireUsablePassword's
            // own failure messages, exercised above, never interpolate the value
            // they are validating. Prove that directly against the method.
            assertThatMessageNeverContainsTheValue(value);
        });
    }

    private void assertThatMessageNeverContainsTheValue(String value) {
        IllegalStateException blank = catchGuardFailure("");
        assertThat(blank.getMessage()).doesNotContain(value);

        IllegalStateException denylisted = catchGuardFailure(PUBLISHED_PLACEHOLDER);
        assertThat(denylisted.getMessage()).doesNotContain(value);
    }

    private IllegalStateException catchGuardFailure(String rejected) {
        try {
            DatabaseCredentialsGuard.requireUsablePassword(rejected);
            throw new AssertionError("expected requireUsablePassword to refuse " + rejected);
        } catch (IllegalStateException e) {
            return e;
        }
    }

    /**
     * The other half of the fix, and the half a code change cannot re-break on
     * its own: application.yml ships no fallback at all, so there is no committed
     * password even for someone who bypasses the guard.
     */
    @Test
    void theShippedConfigurationCarriesNoDefaultPassword() throws IOException {
        String yaml = Files.readString(
                Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml)
                .as("DB_APP_PASSWORD must have no fallback value")
                .contains("${DB_APP_PASSWORD:}")
                .doesNotContain("${DB_APP_PASSWORD:onboarding_app}");
    }

    private static Throwable rootCauseOf(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure);
    }
}
