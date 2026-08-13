package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenTest extends PostgresTestBase {

    @Autowired RefreshTokenService refreshTokens;
    @Autowired RefreshTokenRepository repository;
    @Autowired TenantFixture fixture;

    @Test
    void rotationIssuesANewTokenAndRetiresTheOld() {
        UUID tenant = fixture.createTenant("rotate-co");
        var user = fixture.createUserWithPassword(tenant, "rot@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String first = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            var outcome = refreshTokens.rotate(first);

            assertThat(outcome).isInstanceOf(RotationOutcome.Rotated.class);
            var rotated = (RotationOutcome.Rotated) outcome;
            assertThat(rotated.newRawToken()).isNotEqualTo(first);
            assertThat(rotated.user().getId()).isEqualTo(user.getId());
        });
    }

    /**
     * The security-critical test. Rejecting the replayed token is the easy half;
     * killing the legitimate session is what makes reuse detection meaningful.
     *
     * Each rotate runs in its own runAs, which matters: the family revocation must
     * be COMMITTED, not merely visible inside one transaction. An implementation
     * that revokes and then throws rolls the revocation back, and a single-
     * transaction test cannot tell the difference.
     */
    @Test
    void reusingARetiredTokenRevokesTheWholeFamily() {
        UUID tenant = fixture.createTenant("reuse-co");
        var user = fixture.createUserWithPassword(tenant, "reuse@example.com", "password-value");
        var first = new AtomicReference<String>();
        var second = new AtomicReference<String>();

        fixture.runAs(tenant, () -> first.set(refreshTokens.issue(user, "127.0.0.1", "test-agent")));

        fixture.runAs(tenant, () -> {
            var rotated = (RotationOutcome.Rotated) refreshTokens.rotate(first.get());
            second.set(rotated.newRawToken());
        });

        // Replaying the already-used token means it was stolen.
        fixture.runAs(tenant, () ->
                assertThat(refreshTokens.rotate(first.get()))
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.REUSED)));

        // The token issued to the legitimate client is killed too.
        fixture.runAs(tenant, () ->
                assertThat(refreshTokens.rotate(second.get()))
                        .as("the entire family must be revoked (spec 7.4)")
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.REVOKED)));
    }

    @Test
    void rawTokenIsNeverStored() {
        UUID tenant = fixture.createTenant("hash-co");
        var user = fixture.createUserWithPassword(tenant, "hash@example.com", "password-value");

        fixture.runAs(tenant, () -> {
            String raw = refreshTokens.issue(user, "127.0.0.1", "test-agent");

            assertThat(repository.findAll())
                    .singleElement()
                    .satisfies(stored -> {
                        assertThat(stored.getTokenHash()).isNotEqualTo(raw);
                        // A SHA-256 hex digest, not the token and not some other encoding of it.
                        assertThat(stored.getTokenHash()).matches("^[0-9a-f]{64}$");
                        assertThat(stored.getTokenHash()).doesNotContain(raw);
                    });
        });
    }

    @Test
    void expiredTokenIsRejected() {
        UUID tenant = fixture.createTenant("expiry-co");
        var user = fixture.createUserWithPassword(tenant, "exp@example.com", "password-value");
        var raw = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            raw.set(refreshTokens.issue(user, "127.0.0.1", "test-agent"));
            repository.findAll().forEach(t -> {
                t.setExpiresAt(Instant.now().minusSeconds(60));
                repository.save(t);
            });
        });

        fixture.runAs(tenant, () ->
                assertThat(refreshTokens.rotate(raw.get()))
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.EXPIRED)));
    }

    @Test
    void unknownTokenIsRejected() {
        UUID tenant = fixture.createTenant("unknown-token-co");

        fixture.runAs(tenant, () ->
                assertThat(refreshTokens.rotate("not-a-real-token"))
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.UNKNOWN)));
    }

    /**
     * Not in the plan. refresh_token is RLS-protected and the refresh endpoint runs
     * with the tenant resolved from the path, so a token issued in one tenant must
     * be unusable on another's path — otherwise a stolen cookie would work against
     * any tenant that would accept it. This is the refresh-cookie equivalent of the
     * access token's tid check, and nothing else covers it.
     */
    @Test
    void tokenCannotBeRotatedUnderADifferentTenant() {
        UUID tenantA = fixture.createTenant("refresh-tenant-a");
        UUID tenantB = fixture.createTenant("refresh-tenant-b");
        var userInA = fixture.createUserWithPassword(tenantA, "a@example.com", "password-value");
        var raw = new AtomicReference<String>();

        fixture.runAs(tenantA, () -> raw.set(refreshTokens.issue(userInA, "127.0.0.1", "test-agent")));

        fixture.runAs(tenantB, () ->
                assertThat(refreshTokens.rotate(raw.get()))
                        .as("RLS must hide another tenant's refresh token entirely")
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.UNKNOWN)));

        // And it still works in its own tenant, so the rejection above is isolation
        // rather than the token simply being broken.
        fixture.runAs(tenantA, () ->
                assertThat(refreshTokens.rotate(raw.get()))
                        .isInstanceOf(RotationOutcome.Rotated.class));
    }

    /**
     * Not in the plan. Logout revokes the whole family deliberately — signing out
     * should end the session everywhere it was rotated, not just the newest link.
     */
    @Test
    void logoutRevokesTheFamilySoOlderRotationsAlsoDie() {
        UUID tenant = fixture.createTenant("logout-co");
        var user = fixture.createUserWithPassword(tenant, "out@example.com", "password-value");
        var second = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            String first = refreshTokens.issue(user, "127.0.0.1", "test-agent");
            second.set(((RotationOutcome.Rotated) refreshTokens.rotate(first)).newRawToken());
        });

        fixture.runAs(tenant, () -> refreshTokens.revokeByRawToken(second.get()));

        fixture.runAs(tenant, () ->
                assertThat(refreshTokens.rotate(second.get()))
                        .isEqualTo(new RotationOutcome.Rejected(RotationOutcome.Reason.REVOKED)));
    }
}
