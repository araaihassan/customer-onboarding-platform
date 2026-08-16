package co.ara.onboarding.auth;

import co.ara.onboarding.authz.AuthenticatedPrincipal;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.platform.Uuid7;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class TokenService {

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;

    /**
     * Takes JwtProperties rather than the raw @Value strings so the secret has
     * already been through {@link JwtProperties#validate()} by the time it reaches
     * Keys.hmacShaKeyFor — an unset or under-length secret has failed startup with a
     * message naming JWT_SECRET, instead of surfacing here as a library complaint
     * about key bit lengths, or worse, silently signing with a value committed to
     * this repository.
     */
    public TokenService(JwtProperties properties) {
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.getIssuer();
        this.ttl = properties.getAccessTokenTtl();
    }

    /**
     * Identity claims only — subject, tenant, user type. Authority is resolved per
     * request from the role tables (spec 7.2), so a token cannot carry stale
     * permissions after a role change, and a leaked token grants no more than the
     * account currently has.
     *
     * LoginTest.accessTokenCarriesNoPermissionClaims asserts this by inspecting the
     * decoded payload, so adding a permission-shaped claim here will fail the build.
     */
    public String issueAccessToken(AppUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getId().toString())
                .id(Uuid7.generate().toString())
                .claim("tid", user.getTenantId().toString())
                .claim("typ", user.getUserType().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Empty rather than throwing for any invalid token — bad signature, wrong
     * issuer, expired, malformed. The caller cannot act differently on the reason,
     * and distinguishing them in a response would tell an attacker which part of a
     * forged token to fix.
     */
    public Optional<AuthenticatedPrincipal> parse(String token) {
        try {
            var claims = Jwts.parser().verifyWith(key).requireIssuer(issuer)
                    .build().parseSignedClaims(token).getPayload();
            return Optional.of(new AuthenticatedPrincipal(
                    UUID.fromString(claims.get("tid", String.class)),
                    UUID.fromString(claims.getSubject())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public long ttlSeconds() { return ttl.toSeconds(); }
}
