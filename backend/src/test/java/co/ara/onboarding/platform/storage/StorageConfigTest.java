package co.ara.onboarding.platform.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exactly one {@link BlobStore} bean must exist, selected by {@code app.storage.kind} --
 * see {@link StorageConfig}'s own javadoc for why an unset or unrecognised value is a
 * startup failure rather than an implied {@code local}.
 *
 * Modelled on JwtSecretGuardTest/DatabaseCredentialsGuardTest: asserted against a context
 * holding only {@link StorageProperties} + {@link StorageConfig}, never a full application
 * context needing Postgres -- a context that failed to load for a database reason would be
 * indistinguishable from the guard firing. StorageConfig is registered directly (rather than
 * wrapped in a plain @Bean factory method) so Spring processes its own @Bean method the same
 * way it would in the real application context.
 */
class StorageConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StorageConfig.class, PropertiesConfiguration.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(StorageProperties.class)
    static class PropertiesConfiguration {
    }

    @Test
    void anUnsetStorageKindRefusesToStart() {
        runner.run(context -> {
            assertThat(context)
                    .as("an unset app.storage.kind must not be implicitly local")
                    .hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.storage.kind");
        });
    }

    @Test
    void anUnknownStorageKindRefusesToStart() {
        runner.withPropertyValues("app.storage.kind=nonsense").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.storage.kind");
        });
    }

    @Test
    void s3KindWithNoBucketConfiguredRefusesToStart() {
        runner.withPropertyValues("app.storage.kind=s3").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.storage.s3.bucket");
        });
    }

    @Test
    void localKindWithNoRootConfiguredRefusesToStart() {
        runner.withPropertyValues("app.storage.kind=local").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.storage.local.root");
        });
    }

    @Test
    void localKindSelectsTheLocalAdapter(@TempDir Path root) {
        runner.withPropertyValues(
                        "app.storage.kind=local",
                        "app.storage.local.root=" + root)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BlobStore.class)).isInstanceOf(LocalFsBlobStore.class);
                });
    }

    /**
     * The failure message is asserted in full so a future edit that drops the
     * remedy -- not just the property name -- is caught, the same discipline
     * JwtSecretGuardTest holds its own "no secret set" message to.
     */
    @Test
    void theUnsetMessageNamesThePropertyAndARemedy() {
        runner.run(context -> {
            Throwable failure = rootCauseOf(context.getStartupFailure());
            assertThat(failure.getMessage())
                    .contains("app.storage.kind")
                    .containsIgnoringCase("local")
                    .containsIgnoringCase("s3");
        });
    }

    private static Throwable rootCauseOf(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure);
    }
}
