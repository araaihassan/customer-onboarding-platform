package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserSessionRevoker;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Refresh-token rotation with reuse detection (spec 7.4).
 *
 * Rotation alone is breach *resistance* — a stolen token is only good until the
 * next refresh. Detection comes from never deleting retired tokens: presenting one
 * that has already been rotated is proof that two parties hold the same token, so
 * the entire family is revoked and both the thief and the legitimate client are
 * forced to re-authenticate.
 *
 * NOT annotated with @RequirePermission, and it cannot be: refreshing is how a
 * session stays authenticated, and the credential presented is a cookie rather
 * than an authority. AuthorizationCoverageTest excludes it for the same reason as
 * LoginService.
 */
@Service
public class RefreshTokenService implements UserSessionRevoker {

    private final RefreshTokenRepository repository;
    private final AppUserRepository users;
    private final AuditRecorder audit;
    private final Duration ttl;

    public RefreshTokenService(RefreshTokenRepository repository, AppUserRepository users,
                               AuditRecorder audit,
                               @Value("${app.refresh-token.ttl}") Duration ttl) {
        this.repository = repository;
        this.users = users;
        this.audit = audit;
        this.ttl = ttl;
    }

    /** A fresh family: this is a new login, not a continuation. */
    @Transactional
    public String issue(AppUser user, String ip, String userAgent) {
        return issueInFamily(user, Uuid7.generate(), ip, userAgent);
    }

    @Transactional
    public RotationOutcome rotate(String rawToken) {
        RefreshToken stored = repository.findByTokenHash(hash(rawToken)).orElse(null);
        if (stored == null) {
            return new RotationOutcome.Rejected(RotationOutcome.Reason.UNKNOWN);
        }

        if (stored.getRevokedAt() != null) {
            return new RotationOutcome.Rejected(RotationOutcome.Reason.REVOKED);
        }

        if (stored.getUsedAt() != null) {
            // Replay of an already-rotated token: two parties hold it, so assume
            // theft and kill the family. Both writes below must survive the
            // rejection, which is why this returns rather than throws.
            revokeFamily(stored.getFamilyId());
            audit.record(AuditActions.REFRESH_REUSE_DETECTED, "app_user", stored.getUserId(),
                    "Refresh token reuse detected; session family revoked",
                    Map.of("familyId", stored.getFamilyId().toString()));
            return new RotationOutcome.Rejected(RotationOutcome.Reason.REUSED);
        }

        if (stored.getExpiresAt().isBefore(Instant.now())) {
            // Safe to revoke the family: an unused token is the newest in its
            // family by definition, so nothing live is being cut off.
            revokeFamily(stored.getFamilyId());
            return new RotationOutcome.Rejected(RotationOutcome.Reason.EXPIRED);
        }

        stored.setUsedAt(Instant.now());
        repository.save(stored);

        AppUser user = users.findById(stored.getUserId()).orElse(null);
        if (user == null) {
            revokeFamily(stored.getFamilyId());
            return new RotationOutcome.Rejected(RotationOutcome.Reason.UNKNOWN);
        }

        // A session must not outlive the account. Without this the only check on
        // UserStatus in the whole authenticated flow was LoginService's, which a
        // browser holding a refresh cookie never reaches again: every rotation
        // minted another full-TTL token, so deactivating a departing employee ended
        // nothing until they closed the tab.
        //
        // Checked here rather than trusting the writer: UserAdminService.deactivate
        // revokes the family directly, but deactivation is not the only way a status
        // can change and the next path to change one will not remember. The family
        // is revoked as well as rejected, so the whole chain dies at once rather
        // than one link per attempt.
        //
        // The reason is REVOKED, which the endpoint never discloses -- every
        // rejection is an identical 401, or the response becomes an oracle for
        // which accounts have been deactivated.
        if (user.getStatus() != UserStatus.ACTIVE) {
            revokeFamily(stored.getFamilyId());
            return new RotationOutcome.Rejected(RotationOutcome.Reason.REVOKED);
        }

        String next = issueInFamily(user, stored.getFamilyId(), stored.getIp(), stored.getUserAgent());
        return new RotationOutcome.Rotated(user, next);
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        Instant now = Instant.now();
        repository.findByFamilyId(familyId).forEach(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(now);
                repository.save(t);
            }
        });
    }

    /**
     * Ends every session for a user, across all families. Called when a password
     * changes: whoever prompted the reset may be the attacker, and leaving their
     * family alive would let them keep the account the reset just secured. And on
     * deactivation, for the same reason — a revoked account with a live session is
     * not revoked.
     *
     * Reached from identity through the UserSessionRevoker port; auth already
     * depends on identity, so identity must not name this class.
     */
    @Override
    @Transactional
    public void revokeAllForUser(UUID userId) {
        Instant now = Instant.now();
        repository.findByUserId(userId).forEach(t -> {
            if (t.getRevokedAt() == null) {
                t.setRevokedAt(now);
                repository.save(t);
            }
        });
    }

    /**
     * Logout. Revoking the whole family is intentional: signing out should end the
     * session everywhere it was rotated, not just sever the newest link and leave
     * an older one usable.
     *
     * Silent when the token is unknown — logout is idempotent, and a client
     * clearing a stale cookie should not receive an error.
     */
    @Transactional
    public void revokeByRawToken(String rawToken) {
        repository.findByTokenHash(hash(rawToken))
                .ifPresent(t -> revokeFamily(t.getFamilyId()));
    }

    private String issueInFamily(AppUser user, UUID familyId, String ip, String userAgent) {
        String raw = SecureTokens.generate();

        Instant now = Instant.now();
        RefreshToken token = new RefreshToken();
        token.setId(Uuid7.generate());
        token.setTenantId(user.getTenantId());
        token.setUserId(user.getId());
        token.setTokenHash(hash(raw));
        token.setFamilyId(familyId);
        token.setIssuedAt(now);
        token.setExpiresAt(now.plus(ttl));
        token.setIp(ip);
        token.setUserAgent(userAgent);
        repository.save(token);

        return raw;   // returned once; only the hash is persisted
    }

    /** Shared with invitations and password resets — see SecureTokens for why plain SHA-256. */
    private String hash(String raw) {
        return SecureTokens.hash(raw);
    }
}
