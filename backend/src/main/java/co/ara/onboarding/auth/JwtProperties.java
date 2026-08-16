package co.ara.onboarding.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * The JWT signing configuration, and the guard that refuses to start the
 * application when the signing secret is not one.
 *
 * The secret is the whole of authentication: whoever holds it can mint an access
 * token for any user in any tenant, and JwtAuthenticationFilter cannot tell that
 * token from one a real login issued. So the failure mode this guards against is
 * total authentication bypass, reached by nothing more than a deployment that
 * forgot an environment variable.
 *
 * Deliberately NOT keyed on the active profile. A "fail unless the profile is dev"
 * check misses the deployment that forgot to set the profile as well as the secret,
 * which is the same mistake wearing a different hat — and the profile is exactly
 * the thing a misconfigured deployment gets wrong. The cost is that a developer
 * must set JWT_SECRET explicitly before the application will run at all, which is
 * the point: a signing key that arrives by default is a signing key nobody chose.
 *
 * The check lives here rather than in TokenService's constructor for two reasons.
 * It runs whether or not anything injects it — a Spring singleton is instantiated
 * eagerly at refresh — so the guard cannot be removed by some later refactor that
 * makes token issuance lazy. And it keeps TokenService about tokens: the same shape
 * DescriptorRegistry.validate() already uses in this codebase for "the application
 * refuses to start when a required invariant is unmet".
 */
@Component
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /**
     * HMAC-SHA256 is defined over a 256-bit key. This is not a house policy: JJWT's
     * Keys.hmacShaKeyFor rejects anything shorter outright, and it does so from
     * inside token issuance with a message about bits, naming nothing an operator
     * can act on.
     */
    static final int MINIMUM_SECRET_BYTES = 32;

    /**
     * Values that have been committed to this repository as placeholders. Any of
     * them is public knowledge, so it is a forged token for every account in every
     * tenant. A denylist entry, not key material — the fallback in application.yml
     * was removed in the same change, and this exists for the deployment that copies
     * the old placeholder out of the history or the docs into a real JWT_SECRET.
     */
    private static final Set<String> PUBLISHED_PLACEHOLDERS = Set.of(
            "dev-only-secret-replace-in-production-min-32-bytes");

    private static final String REMEDY =
            " Set the JWT_SECRET environment variable to at least " + MINIMUM_SECRET_BYTES
                    + " bytes of unpredictable data (for example: openssl rand -base64 48)."
                    + " The application will not start without one, on any profile.";

    private String secret;
    private String issuer;
    private Duration accessTokenTtl;

    @PostConstruct
    public void validate() {
        requireUsableSecret(secret);
    }

    /**
     * Never includes the rejected value in the message. The "too short" branch in
     * particular is reached by real secrets that were merely mis-sized, and a
     * startup failure is written to stdout and to whatever collects it.
     */
    static void requireUsableSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("app.jwt.secret is not set." + REMEDY);
        }
        if (PUBLISHED_PLACEHOLDERS.contains(secret)) {
            throw new IllegalStateException(
                    "app.jwt.secret is a placeholder value published in this repository's source,"
                            + " so anyone who can read the source can forge an access token for any"
                            + " user in any tenant." + REMEDY);
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MINIMUM_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret is " + bytes + " bytes; HMAC-SHA256 signing requires at least "
                            + MINIMUM_SECRET_BYTES + "." + REMEDY);
        }
    }

    public String getSecret() { return secret; }

    public void setSecret(String secret) { this.secret = secret; }

    public String getIssuer() { return issuer; }

    public void setIssuer(String issuer) { this.issuer = issuer; }

    public Duration getAccessTokenTtl() { return accessTokenTtl; }

    public void setAccessTokenTtl(Duration accessTokenTtl) { this.accessTokenTtl = accessTokenTtl; }
}
