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
     * The suite runs against src/test/resources/application-test.yml, not a fallback
     * in the shipped configuration — if this ever reads the placeholder again, the
     * guard has been routed around rather than satisfied.
     */
    @Test
    void theSuiteSuppliesItsOwnSecret() {
        assertThat(properties.getSecret())
                .isEqualTo("test-only-jwt-signing-secret-of-at-least-32-bytes");
    }
}
