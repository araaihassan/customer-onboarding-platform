package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LoginTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TokenService tokens;
    @Autowired TenantFixture fixture;

    @Test
    void loginReturnsAccessTokenForValidCredentials() throws Exception {
        UUID tenant = fixture.createTenant("login-co");
        fixture.createUserWithPassword(tenant, "user@login.example", "correct-horse-battery");

        String body = mvc.perform(post("/api/t/login-co/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "user@login.example",
                                   "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = json.readTree(body).get("accessToken").asText();
        assertThat(tokens.parse(token)).isPresent();
    }

    @Test
    void accessTokenCarriesNoPermissionClaims() throws Exception {
        UUID tenant = fixture.createTenant("no-perm-claims");
        var user = fixture.createUserWithPassword(tenant, "np@example.com", "a-long-password");

        String token = tokens.issueAccessToken(user);
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(token.split("\\.")[1]));

        assertThat(payload)
            .as("permissions must be resolved server-side, never carried in the token (spec 7.2)")
            .doesNotContain("permission").doesNotContain("scope").doesNotContain("role");
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        UUID tenant = fixture.createTenant("wrong-pw");
        fixture.createUserWithPassword(tenant, "wp@example.com", "the-right-password");

        mvc.perform(post("/api/t/wrong-pw/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "wp@example.com", "password", "the-wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void nonActiveUserCannotLogIn() throws Exception {
        UUID tenant = fixture.createTenant("invited-user");
        fixture.createInvitedUser(tenant, "pending@example.com", "some-password");

        mvc.perform(post("/api/t/invited-user/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "pending@example.com", "password", "some-password"))))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Spec 7.8 reserves a place for MFA without implementing it. Failing closed is
     * the point: an account flagged for MFA must not be able to sign in with a
     * password alone just because the second factor is unimplemented. 501 rather
     * than 401 because the credentials were correct — this is a missing capability,
     * not a rejected identity.
     */
    @Test
    void mfaEnabledUserIsRefusedRatherThanIssuedAToken() throws Exception {
        UUID tenant = fixture.createTenant("mfa-user");
        fixture.createUserWithPassword(tenant, "mfa@example.com", "correct-password", true);

        mvc.perform(post("/api/t/mfa-user/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", "mfa@example.com", "password", "correct-password"))))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.accessToken").doesNotExist());
    }

    /**
     * Not in the plan. A token is valid only for the tenant it was issued for, and
     * JwtAuthenticationFilter enforces that by comparing the token's tid claim
     * against the resolved tenant. Nothing else in this task exercises the filter,
     * so the check could be missing or inverted and every other test here would
     * still pass. Task 22's negative suite depends on this holding.
     *
     * Both directions are asserted, against a path with no handler. 401 means the
     * security chain rejected the request before routing; 404 means it authenticated
     * and got as far as looking for a controller. Without the same-tenant half, a
     * filter that rejected everything would satisfy the cross-tenant assertion.
     */
    @Test
    void tokenIsAcceptedOnlyForTheTenantItWasIssuedFor() throws Exception {
        UUID tenantA = fixture.createTenant("token-tenant-a");
        fixture.createTenant("token-tenant-b");
        var userInA = fixture.createUserWithPassword(tenantA, "a@example.com", "a-password");

        String tokenForA = tokens.issueAccessToken(userInA);

        mvc.perform(post("/api/t/token-tenant-b/no-such-endpoint")
                    .header("Authorization", "Bearer " + tokenForA))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/t/token-tenant-a/no-such-endpoint")
                    .header("Authorization", "Bearer " + tokenForA))
                .andExpect(status().isNotFound());
    }
}
