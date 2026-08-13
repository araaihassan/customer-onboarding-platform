package co.ara.onboarding.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

/**
 * Deliberately NOT @Transactional on any method.
 *
 * Both rejection paths answer with a thrown ResponseStatusException, and both
 * services perform writes before rejecting — LoginService records LOGIN_FAILED,
 * RefreshTokenService revokes a family and records REFRESH_REUSE_DETECTED. If a
 * transaction were open here, throwing would roll those writes back. The services
 * own their transactions, commit them, and return an outcome; the controller
 * translates that outcome into a status once no transaction is open.
 */
@RestController
@RequestMapping("/api/t/{tenantSlug}/auth")
public class AuthController {

    public record LoginRequest(@Email String email, @NotBlank String password) {}
    public record LoginResponse(String accessToken, long expiresInSeconds, Map<String, Object> user) {}

    private final LoginService logins;
    private final RefreshTokenService refreshTokens;
    private final TokenService tokens;
    private final Duration refreshTtl;

    public AuthController(LoginService logins, RefreshTokenService refreshTokens,
                          TokenService tokens,
                          @Value("${app.refresh-token.ttl}") Duration refreshTtl) {
        this.logins = logins;
        this.refreshTokens = refreshTokens;
        this.tokens = tokens;
        this.refreshTtl = refreshTtl;
    }

    /**
     * HttpOnly so script cannot read it, Secure so it never crosses plaintext,
     * SameSite=Strict so no cross-site request can trigger a refresh, and scoped to
     * this tenant's auth path so it is not attached to ordinary API calls at all.
     *
     * Secure works in local development because browsers treat http://localhost as
     * a secure context.
     */
    private ResponseCookie refreshCookie(String tenantSlug, String rawToken, Duration maxAge) {
        return ResponseCookie.from("refresh_token", rawToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/api/t/" + tenantSlug + "/auth")
                .maxAge(maxAge)
                .build();
    }

    /**
     * Both failure branches return no token and no detail beyond the status. The
     * 401 body is identical whether the account is absent, inactive, or the password
     * is wrong — see LoginOutcome.InvalidCredentials.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@PathVariable String tenantSlug,
                                               @Valid @RequestBody LoginRequest request) {
        return switch (logins.login(request.email(), request.password())) {
            case LoginOutcome.Success s -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            refreshCookie(tenantSlug, s.refreshToken(), refreshTtl).toString())
                    .body(new LoginResponse(s.accessToken(), s.expiresInSeconds(),
                            Map.of("id", s.userId(), "fullName", s.fullName(),
                                   "userType", s.userType().name())));

            case LoginOutcome.InvalidCredentials ignored ->
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");

            // 501, not 401: the credentials were correct, so this is a missing
            // capability rather than a rejected identity.
            case LoginOutcome.MfaRequired ignored ->
                    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                            "MFA is not yet supported");

            // 429 rather than a sixth 401, so the client can say "try again later"
            // instead of "wrong password". Task 25 distinguishes the two messages.
            case LoginOutcome.LockedOut ignored ->
                    throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                            "Too many attempts");
        };
    }

    /**
     * One 401 for every rejection reason. Distinguishing "expired" from "unknown"
     * would tell a caller holding a stolen cookie whether it was ever valid.
     */
    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @PathVariable String tenantSlug,
            @CookieValue(name = "refresh_token", required = false) String rawToken) {

        if (rawToken == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }

        return switch (refreshTokens.rotate(rawToken)) {
            case RotationOutcome.Rotated r -> ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE,
                            refreshCookie(tenantSlug, r.newRawToken(), refreshTtl).toString())
                    .body(new LoginResponse(tokens.issueAccessToken(r.user()), tokens.ttlSeconds(),
                            Map.of("id", r.user().getId(),
                                   "fullName", r.user().getFullName(),
                                   "userType", r.user().getUserType().name())));

            case RotationOutcome.Rejected ignored ->
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token");
        };
    }

    /** Idempotent: an absent or unknown cookie still clears and still answers 204. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @PathVariable String tenantSlug,
            @CookieValue(name = "refresh_token", required = false) String rawToken) {

        if (rawToken != null) refreshTokens.revokeByRawToken(rawToken);

        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE,
                        refreshCookie(tenantSlug, "", Duration.ZERO).toString())
                .build();
    }
}
