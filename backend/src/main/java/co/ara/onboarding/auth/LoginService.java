package co.ara.onboarding.auth;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.RequestAuditContext;
import co.ara.onboarding.tenancy.TenantContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Credential verification and access-token issue.
 *
 * NOT annotated with @RequirePermission, and it cannot be: this is how a caller
 * becomes authenticated in the first place, so requiring a permission to use it
 * would be unsatisfiable. AuthorizationCoverageTest excludes it by name for that
 * reason — the same category as TenantProvisioningService, which also runs before
 * any authenticated actor exists.
 *
 * It exists as a service rather than living in AuthController because controllers
 * must not touch repositories (ModuleBoundaryTest); a repository call outside a
 * @Transactional service has no tenant bound.
 */
@Service
public class LoginService {

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final AuditRecorder audit;
    private final RequestAuditContext requestContext;

    public LoginService(AppUserRepository users, PasswordEncoder passwords,
                        TokenService tokens, RefreshTokenService refreshTokens,
                        AuditRecorder audit, RequestAuditContext requestContext) {
        this.users = users;
        this.passwords = passwords;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.audit = audit;
        this.requestContext = requestContext;
    }

    @Transactional
    public LoginOutcome login(String email, String rawPassword) {
        AppUser user = users.findByTenantIdAndEmailIgnoreCase(
                TenantContext.getRequired(), email).orElse(null);

        boolean valid = user != null
                && user.getStatus() == UserStatus.ACTIVE
                && user.getPasswordHash() != null
                && passwords.matches(rawPassword, user.getPasswordHash());

        if (!valid) {
            audit.record(AuditActions.LOGIN_FAILED, "app_user",
                    user == null ? null : user.getId(),
                    "Failed login for " + email, Map.of());
            return new LoginOutcome.InvalidCredentials();
        }

        // Reserved MFA challenge (spec 7.8). TOTP verification lands in a later
        // sub-project; the branch exists now so adding it is not a flow redesign.
        // Failing closed is deliberate: an account flagged for MFA must not be able
        // to sign in with a password alone just because the second factor is
        // unimplemented. Checked AFTER credential verification so an attacker cannot
        // use the 501 to discover which accounts have MFA enabled.
        if (user.isMfaEnabled()) {
            return new LoginOutcome.MfaRequired();
        }

        user.setLastLoginAt(Instant.now());
        users.save(user);
        audit.record(AuditActions.LOGIN_SUCCEEDED, "app_user", user.getId(),
                "Successful login", Map.of());

        // A successful login starts a NEW token family. Reusing one would mean a
        // reuse detection on an old session could revoke a freshly logged-in one.
        String refreshToken = refreshTokens.issue(
                user, requestContext.ip(), requestContext.userAgent());

        return new LoginOutcome.Success(
                tokens.issueAccessToken(user), tokens.ttlSeconds(), refreshToken,
                user.getId(), user.getFullName(), user.getUserType());
    }
}
