package co.ara.onboarding.auth;

import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Password reset request and confirmation, both performed by an unauthenticated
 * caller — the whole point is that they cannot log in. Not permission-gated, and
 * excluded from AuthorizationCoverageTest in the pre-authentication category.
 */
@Service
public class PasswordResetService {

    /** One hour, against activation's seven days: a reset link is a live credential
     *  to an existing account, so its window should be as short as usability allows. */
    static final Duration RESET_TTL = Duration.ofHours(1);

    private final InvitationRepository invitations;
    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final RefreshTokenService refreshTokens;
    private final EmailSender email;

    public PasswordResetService(InvitationRepository invitations, AppUserRepository users,
                                PasswordEncoder passwords, RefreshTokenService refreshTokens,
                                EmailSender email) {
        this.invitations = invitations;
        this.users = users;
        this.passwords = passwords;
        this.refreshTokens = refreshTokens;
        this.email = email;
    }

    /**
     * Empty for an unknown address, and the caller must not turn that into a
     * different response — the endpoint answers 204 either way, or it becomes an
     * account-enumeration oracle. The raw token is returned for the dev email sender
     * and for tests; in production only the email carries it.
     */
    @Transactional
    public Optional<String> request(String rawEmail) {
        AppUser user = users.findByTenantIdAndEmailIgnoreCase(
                TenantContext.getRequired(), rawEmail).orElse(null);
        if (user == null) return Optional.empty();

        String raw = SecureTokens.generate();

        Invitation reset = new Invitation();
        reset.setId(Uuid7.generate());
        reset.setTenantId(user.getTenantId());
        reset.setPurpose(InvitationPurpose.PASSWORD_RESET);
        reset.setUserId(user.getId());
        reset.setTokenHash(SecureTokens.hash(raw));
        reset.setExpiresAt(Instant.now().plus(RESET_TTL));
        invitations.save(reset);

        email.send(new EmailMessage(user.getEmail(), "Reset your password",
                "Use this token to reset your password: " + raw));

        return Optional.of(raw);
    }

    @Transactional
    public void reset(String rawToken, String newPassword) {
        // The purpose check is what stops a seven-day activation token being redeemed
        // as a one-hour password reset. Both live in one table, so nothing else
        // separates them.
        Invitation reset = invitations.findByTokenHash(SecureTokens.hash(rawToken))
                .filter(i -> i.isRedeemable(InvitationPurpose.PASSWORD_RESET, Instant.now()))
                .orElseThrow(() -> new InvalidTokenException("Reset token is not redeemable"));

        AppUser user = users.findById(reset.getUserId())
                .orElseThrow(() -> new InvalidTokenException("Reset token has no user"));

        user.setPasswordHash(passwords.encode(newPassword));
        users.save(user);

        reset.setAcceptedAt(Instant.now());
        invitations.save(reset);

        // Changing a password ends existing sessions. Whoever prompted the reset may
        // be the attacker, and leaving their refresh family alive would let them keep
        // the account they were just locked out of.
        refreshTokens.revokeAllForUser(user.getId());
    }
}
