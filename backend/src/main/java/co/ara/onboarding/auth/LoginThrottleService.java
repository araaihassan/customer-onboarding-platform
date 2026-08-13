package co.ara.onboarding.auth;

import co.ara.onboarding.platform.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Per-account login throttling, counted in PostgreSQL since Redis is out of scope.
 *
 * Counting per (tenant, email) rather than per IP is deliberate: it protects an
 * account against distributed guessing, which IP-based counting does not. The cost
 * is that an attacker can lock a known address out on purpose; that trade is the
 * standard one, and the 15-minute window bounds it.
 *
 * NOT annotated with @RequirePermission: this runs during authentication, before
 * there is an actor to authorize. AuthorizationCoverageTest excludes it in the same
 * category as LoginService and RefreshTokenService.
 */
@Service
public class LoginThrottleService {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final LoginAttemptRepository attempts;

    public LoginThrottleService(LoginAttemptRepository attempts) { this.attempts = attempts; }

    /**
     * Root-lowercase, matching app_user's and V10's indexes on lower(email).
     *
     * Two separate mechanisms are at work here and it is worth being exact about
     * which does what, because they fail differently. V10's unique index on
     * (tenant_id, lower(email)) is what makes the *count* correct: the UPSERT's
     * conflict target matches across capitalisations, so five differently-cased
     * attempts land on one row whether or not this method lowercases. This method is
     * what makes the *lookup* correct: findByTenantIdAndEmail is an exact match, so
     * without it isLockedOut misses a row stored under another casing.
     *
     * Removing the lowercasing was tested. The count stayed right and isLockedOut
     * returned false — the account was locked and the check silently did not fire,
     * which is a worse failure than an off-by-one count because nothing looks wrong.
     *
     * Locale.ROOT rather than the default locale: in a Turkish locale
     * "I".toLowerCase() is "ı", which would not match the database's lower().
     */
    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional(readOnly = true)
    public boolean isLockedOut(UUID tenantId, String email) {
        return attempts.findByTenantIdAndEmail(tenantId, normalise(email))
                .map(LoginAttempt::getLockedUntil)
                .map(until -> until.isAfter(Instant.now()))
                .orElse(false);
    }

    /**
     * Must be called for unknown addresses too, not only for real accounts. If only
     * existing users could ever lock out, 429-versus-401 would tell an attacker
     * which addresses exist and undo the shared 401 that exists to hide it.
     */
    @Transactional
    public void recordFailure(UUID tenantId, String email) {
        Instant now = Instant.now();
        String normalised = normalise(email);

        attempts.upsertFailure(Uuid7.generate(), tenantId, normalised, now, now.minus(WINDOW));
        attempts.applyLockIfThresholdReached(
                tenantId, normalised, MAX_FAILURES, now.plus(LOCKOUT), now);
    }

    /** Clears the counter outright — a successful login means the failures were noise. */
    @Transactional
    public void recordSuccess(UUID tenantId, String email) {
        attempts.deleteByTenantIdAndEmail(tenantId, normalise(email));
    }
}
