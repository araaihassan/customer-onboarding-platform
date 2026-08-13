package co.ara.onboarding.auth;

import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class LoginThrottleTest extends PostgresTestBase {

    @Autowired LoginThrottleService throttle;
    @Autowired LoginAttemptRepository attempts;
    @Autowired TenantFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void locksOutAfterFiveConsecutiveFailures() {
        UUID tenant = fixture.createTenant("throttle-co");

        fixture.runAs(tenant, () -> {
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "target@example.com");
            assertThat(throttle.isLockedOut(tenant, "target@example.com"))
                    .as("four failures is under the threshold")
                    .isFalse();

            throttle.recordFailure(tenant, "target@example.com");
            assertThat(throttle.isLockedOut(tenant, "target@example.com")).isTrue();
        });
    }

    @Test
    void successClearsTheCounter() {
        UUID tenant = fixture.createTenant("throttle-clear");

        fixture.runAs(tenant, () -> {
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "clear@example.com");
            throttle.recordSuccess(tenant, "clear@example.com");
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "clear@example.com");

            assertThat(throttle.isLockedOut(tenant, "clear@example.com"))
                    .as("the successful login reset the count, so four more is still under five")
                    .isFalse();
        });
    }

    /**
     * Also an RLS test: login_attempt is tenant-scoped, so tenant B cannot even see
     * tenant A's counter. Without that, one tenant could lock an address out of
     * another tenant's application.
     */
    @Test
    void lockoutIsScopedPerTenantAndEmail() {
        UUID tenantA = fixture.createTenant("throttle-a");
        UUID tenantB = fixture.createTenant("throttle-b");

        fixture.runAs(tenantA, () -> {
            for (int i = 0; i < 5; i++) throttle.recordFailure(tenantA, "same@example.com");
        });

        fixture.runAs(tenantA, () ->
                assertThat(throttle.isLockedOut(tenantA, "same@example.com")).isTrue());
        fixture.runAs(tenantB, () ->
                assertThat(throttle.isLockedOut(tenantB, "same@example.com")).isFalse());
    }

    /**
     * Not in the plan, and it is a real bypass. Every other email lookup in the
     * system is case-insensitive — app_user has a unique index on lower(email) — so
     * counting throttle failures against the raw string would give an attacker five
     * fresh attempts per capitalisation of the same address.
     */
    @Test
    void lockoutIgnoresEmailCase() {
        UUID tenant = fixture.createTenant("throttle-case");

        fixture.runAs(tenant, () -> {
            throttle.recordFailure(tenant, "Mixed@Example.com");
            throttle.recordFailure(tenant, "mixed@example.com");
            throttle.recordFailure(tenant, "MIXED@EXAMPLE.COM");
            throttle.recordFailure(tenant, "mIxEd@eXaMpLe.CoM");
            throttle.recordFailure(tenant, "mixed@EXAMPLE.com");

            assertThat(throttle.isLockedOut(tenant, "mixed@example.com"))
                    .as("five failures across capitalisations are five failures for one account")
                    .isTrue();

            assertThat(attempts.findAll())
                    .as("and they must be one row, not five")
                    .hasSize(1);
        });
    }

    /**
     * Not in the plan. The window is what stops a slow trickle of failures from
     * accumulating into a lockout forever, and nothing else exercises it. Ageing
     * first_failure past the window is the only way to test it without waiting
     * fifteen minutes.
     */
    @Test
    void failuresOutsideTheWindowStartAFreshCount() {
        UUID tenant = fixture.createTenant("throttle-window");

        fixture.runAs(tenant, () -> {
            for (int i = 0; i < 4; i++) throttle.recordFailure(tenant, "slow@example.com");

            attempts.findAll().forEach(a -> {
                a.setFirstFailure(Instant.now().minusSeconds(16 * 60));
                attempts.save(a);
            });

            throttle.recordFailure(tenant, "slow@example.com");

            assertThat(throttle.isLockedOut(tenant, "slow@example.com"))
                    .as("the earlier four aged out, so this is failure one of a new window")
                    .isFalse();
            assertThat(attempts.findAll()).singleElement()
                    .extracting(LoginAttempt::getFailureCount).isEqualTo(1);
        });
    }

    /**
     * Not in the plan: proves the policy is actually wired into the endpoint and
     * surfaces as 429 rather than a sixth 401. Task 25's UI distinguishes the two
     * (auth.login.lockedOut vs auth.login.error), so the status is a contract.
     */
    @Test
    void sixthAttemptOverHttpAnswers429() throws Exception {
        UUID tenant = fixture.createTenant("throttle-http");
        fixture.createUserWithPassword(tenant, "http@example.com", "the-right-password");

        String body = json.writeValueAsString(
                Map.of("email", "http@example.com", "password", "wrong-password"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/t/throttle-http/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/t/throttle-http/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());

        // Locked out means locked out — the correct password does not get you in.
        mvc.perform(post("/api/t/throttle-http/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("email", "http@example.com", "password", "the-right-password"))))
                .andExpect(status().isTooManyRequests());
    }

    /**
     * Not in the plan, and the reason the throttle must count unknown addresses too.
     * If only real accounts could ever lock out, then 429 versus 401 would tell an
     * attacker which addresses exist — reintroducing exactly the enumeration oracle
     * that the single shared 401 exists to prevent.
     */
    @Test
    void unknownAddressesAreThrottledSoTheStatusLeaksNothing() throws Exception {
        UUID tenant = fixture.createTenant("throttle-unknown");

        String body = json.writeValueAsString(
                Map.of("email", "no-such-user@example.com", "password", "whatever"));

        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/t/throttle-unknown/auth/login")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/t/throttle-unknown/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests());
    }
}
