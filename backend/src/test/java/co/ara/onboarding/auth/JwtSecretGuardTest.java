package co.ara.onboarding.auth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The JWT signing secret is the whole of authentication: anyone holding it can mint
 * an access token for any user in any tenant, and nothing downstream can tell the
 * forgery from a real login. A deployment that forgets to set it must therefore fail
 * loudly at startup rather than quietly sign with whatever happens to be in the
 * repository.
 *
 * These assertions are made against the JwtProperties bean rather than a whole
 * application context on purpose: a full context needs Postgres, and a context that
 * failed to load for a database reason would be indistinguishable from the guard
 * firing. Here the only bean in the context is the one under test, so a failure can
 * only be the guard. JwtPropertiesWiringTest closes the loop by asserting the bean is
 * really in the running application.
 */
class JwtSecretGuardTest {

    /**
     * The value application.yml carried as its default until this guard existed, and
     * which is still readable in this repository's history. It is a denylist entry,
     * not key material.
     */
    private static final String PUBLISHED_PLACEHOLDER =
            "dev-only-secret-replace-in-production-min-32-bytes";

    /**
     * 32 bytes exactly is the HMAC-SHA256 minimum; this is 48. A fixture, never
     * offered as deployable configuration, which is why it is not on
     * JwtProperties' denylist while the two former defaults are.
     */
    private static final String USABLE_SECRET =
            "a-perfectly-ordinary-secret-of-sufficient-length";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JwtPropertiesConfiguration.class)
            .withPropertyValues(
                    "app.jwt.issuer=onboarding-platform",
                    "app.jwt.access-token-ttl=PT15M");

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesConfiguration {
    }

    @Test
    void refusesToStartOnTheSecretPublishedInThisRepository() {
        runner.withPropertyValues("app.jwt.secret=" + PUBLISHED_PLACEHOLDER).run(context -> {
            assertThat(context)
                    .as("a secret readable by anyone who can read the source is not a secret")
                    .hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        });
    }

    /**
     * The guard originally listed only application.yml's former default, while the
     * same change published two more working secrets — the backend suite's and the
     * e2e harness's — both of which CLAUDE.md pointed readers at. Both are now
     * generated per run instead of written down, and both old literals are
     * denylisted for the deployment that copies one out of the history.
     *
     * Each is asserted individually rather than in a loop, so a failure names the
     * value that is no longer refused.
     */
    @Test
    void refusesToStartOnTheFormerBackendSuiteSecret() {
        assertRefuses("test-only-jwt-signing-secret-of-at-least-32-bytes");
    }

    @Test
    void refusesToStartOnTheFormerE2eHarnessSecret() {
        assertRefuses("e2e-only-secret-at-least-thirty-two-bytes-long");
    }

    private void assertRefuses(String publishedSecret) {
        runner.withPropertyValues("app.jwt.secret=" + publishedSecret).run(context -> {
            assertThat(context)
                    .as("a secret this repository published is a secret nobody has")
                    .hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET");
        });
    }

    /**
     * The message is pinned in full, not sampled. It is the entire user interface of
     * this guard — the operator reading it has a process that will not start and no
     * other clue — so it has to name the variable, the size, and how to produce one.
     * If a reword makes this fail, keep all three.
     */
    @Test
    void refusesToStartWhenNoSecretIsSet() {
        runner.withPropertyValues("app.jwt.secret=").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("app.jwt.secret is not set."
                            + " Set the JWT_SECRET environment variable to at least 32 bytes of"
                            + " unpredictable data (for example: openssl rand -base64 48)."
                            + " The application will not start without one, on any profile.");
        });
    }

    /**
     * Thirty-one bytes. Not a policy preference: HMAC-SHA256 is defined over a
     * 256-bit key, and Keys.hmacShaKeyFor rejects anything shorter — but it does so
     * with a library message about key lengths in bits, from deep inside token
     * issuance. The guard's job is to say which environment variable is wrong.
     */
    @Test
    void refusesToStartOnASecretShorterThanHmacSha256Needs() {
        String tooShort = "x".repeat(31);
        assertThat(tooShort.getBytes(StandardCharsets.UTF_8)).hasSize(31);

        runner.withPropertyValues("app.jwt.secret=" + tooShort).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootCauseOf(context.getStartupFailure()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("JWT_SECRET")
                    .hasMessageContaining("32");
        });
    }

    /** Length is measured in bytes, not characters — a UTF-8 char can be up to four. */
    @Test
    void refusesToStartOnASecretWhoseCharacterCountFlattersItsByteCount() {
        String thirtyOneBytes = "é".repeat(15) + "x"; // 15 x 2 bytes + 1
        assertThat(thirtyOneBytes.getBytes(StandardCharsets.UTF_8)).hasSize(31);
        assertThat(thirtyOneBytes).hasSize(16);

        runner.withPropertyValues("app.jwt.secret=" + thirtyOneBytes).run(context -> {
            assertThat(context)
                    .as("31 bytes is 31 bytes however few characters carried them")
                    .hasFailed();
        });
    }

    @Test
    void startsWithASecretOfAtLeastThirtyTwoBytes() {
        assertThat(USABLE_SECRET.getBytes(StandardCharsets.UTF_8)).hasSizeGreaterThanOrEqualTo(32);

        runner.withPropertyValues("app.jwt.secret=" + USABLE_SECRET).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JwtProperties.class);
            assertThat(context.getBean(JwtProperties.class).getSecret()).isEqualTo(USABLE_SECRET);
        });
    }

    /**
     * The message is written to stdout and to whatever collects it. A guard that
     * printed the rejected value would publish a secret that, on the "too short"
     * branch, may well be a real one someone merely mis-sized.
     */
    @Test
    void theFailureNeverEchoesTheSecret() {
        String tooShort = "correct-horse-battery-stap"; // 26 bytes

        runner.withPropertyValues("app.jwt.secret=" + tooShort).run(context -> {
            assertThat(context).hasFailed();
            Throwable failure = context.getStartupFailure();
            assertThat(everyMessageIn(failure))
                    .as("the rejected secret must not appear anywhere in the failure")
                    .doesNotContain(tooShort);
        });
    }

    /**
     * The other half of the fix, and the half a code change cannot re-break on its
     * own: application.yml ships no fallback at all, so there is no committed signing
     * key even for someone who bypasses the guard. Kept as an assertion because the
     * obvious "convenience" edit is to put one back.
     */
    @Test
    void theShippedConfigurationCarriesNoDefaultSecret() throws IOException {
        String yaml = Files.readString(
                Path.of("src", "main", "resources", "application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml)
                .as("JWT_SECRET must have no fallback value")
                .contains("${JWT_SECRET:}")
                .doesNotContain(PUBLISHED_PLACEHOLDER);
    }

    private static Throwable rootCauseOf(Throwable failure) {
        return NestedExceptionUtils.getMostSpecificCause(failure);
    }

    private static String everyMessageIn(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.append(t.getMessage()).append('\n');
        }
        return messages.toString();
    }
}
