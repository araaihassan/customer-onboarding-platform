package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtSecretGuardTest proves the guard rejects what it should, in a context holding
 * nothing else. This proves the guarded bean is actually in the running application
 * and that token issuance goes through it — without which the guard would be a
 * correct check nobody calls.
 *
 * A Spring singleton is instantiated eagerly at refresh, so the guard runs at startup
 * whether or not anything injects it; TokenService's constructor dependency is the
 * second, independent reason it cannot be skipped.
 */
class JwtPropertiesWiringTest extends PostgresTestBase {

    @Autowired ApplicationContext context;
    @Autowired JwtProperties properties;

    @Test
    void theApplicationContextHoldsTheGuardedProperties() {
        assertThat(context.getBeanNamesForType(JwtProperties.class))
                .as("no bean, no startup guard")
                .hasSize(1);
    }

    @Test
    void tokenIssuanceDependsOnTheGuardedProperties() {
        assertThat(TokenService.class.getDeclaredConstructors())
                .as("a TokenService taking a raw secret string would bypass the guard")
                .allSatisfy(c -> assertThat(c.getParameterTypes()).containsExactly(JwtProperties.class));
    }

    /**
     * The suite supplies its own secret — generated per run by PostgresTestBase —
     * rather than inheriting a fallback from the shipped configuration. If this ever
     * reads a published value again, the guard has been routed around rather than
     * satisfied.
     *
     * This asserted the literal from src/test/resources/application-test.yml until
     * that file was deleted: a secret written down in the repository is a published
     * secret, which is exactly what the denylist exists to reject. The assertion is
     * now on the PROPERTY the literal was standing in for, so it survives the value
     * changing on every run and still fails if a placeholder comes back.
     */
    @Test
    void theSuiteSuppliesItsOwnSecret() {
        String secret = properties.getSecret();

        // The guard itself is the oracle: null, blank, denylisted or too short all
        // throw. Asserting through it means this cannot drift from the real rule.
        JwtProperties.requireUsableSecret(secret);

        assertThat(secret)
                .as("every secret this repository has ever published is now denylisted")
                .isNotIn("dev-only-secret-replace-in-production-min-32-bytes",
                        "test-only-jwt-signing-secret-of-at-least-32-bytes",
                        "e2e-only-secret-at-least-thirty-two-bytes-long");
    }
}
