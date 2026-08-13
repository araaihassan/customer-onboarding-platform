package co.ara.onboarding.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/api/t/{tenantSlug}/auth")
public class AuthController {

    public record LoginRequest(@Email String email, @NotBlank String password) {}
    public record LoginResponse(String accessToken, long expiresInSeconds, Map<String, Object> user) {}

    private final LoginService logins;

    public AuthController(LoginService logins) { this.logins = logins; }

    /**
     * Both failure branches return no token and no detail beyond the status. The
     * 401 body is identical whether the account is absent, inactive, or the password
     * is wrong — see LoginOutcome.InvalidCredentials.
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return switch (logins.login(request.email(), request.password())) {
            case LoginOutcome.Success s -> new LoginResponse(
                    s.accessToken(), s.expiresInSeconds(),
                    Map.of("id", s.userId(), "fullName", s.fullName(),
                           "userType", s.userType().name()));

            case LoginOutcome.InvalidCredentials ignored ->
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");

            // 501, not 401: the credentials were correct, so this is a missing
            // capability rather than a rejected identity.
            case LoginOutcome.MfaRequired ignored ->
                    throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                            "MFA is not yet supported");
        };
    }
}
