package co.ara.onboarding.auth;

import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PasswordResetTest extends PostgresTestBase {

    @Autowired PasswordResetService resets;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TenantFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void resetReplacesThePasswordHash() {
        UUID tenant = fixture.createTenant("reset-co");
        var user = fixture.createUserWithPassword(tenant, "reset@example.com", "the-old-password");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> token.set(resets.request("reset@example.com").orElseThrow()));

        fixture.runAs(tenant, () -> resets.reset(token.get(), "a-brand-new-password"));

        fixture.runAs(tenant, () -> {
            String hash = users.findById(user.getId()).orElseThrow().getPasswordHash();
            assertThat(passwords.matches("a-brand-new-password", hash)).isTrue();
            assertThat(passwords.matches("the-old-password", hash))
                    .as("the old password must stop working")
                    .isFalse();
        });
    }

    @Test
    void aResetTokenCannotBeUsedTwice() {
        UUID tenant = fixture.createTenant("reset-once");
        fixture.createUserWithPassword(tenant, "once@example.com", "the-old-password");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> token.set(resets.request("once@example.com").orElseThrow()));
        fixture.runAs(tenant, () -> resets.reset(token.get(), "first-new-password"));

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> resets.reset(token.get(), "second-new-password")))
                .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * An activation token must not be usable to reset a password, or vice versa. Both
     * live in one table distinguished only by `purpose`, so nothing but an explicit
     * check keeps them apart — and an activation token is valid for seven days
     * against a password reset's one hour.
     */
    @Test
    void anActivationTokenCannotBeUsedToResetAPassword() {
        UUID tenant = fixture.createTenant("purpose-check");
        var activationToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Purpose Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "purpose@example.com");
            activationToken.set(fixture.issueInvitation(contactId));
        });

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> resets.reset(activationToken.get(), "a-brand-new-password")))
                .as("purpose must be checked, not just token validity")
                .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * The endpoint must answer identically whether or not the address exists, or it
     * becomes an account-enumeration oracle — the same property the shared 401 gives
     * login.
     */
    @Test
    void requestAnswersTheSameForKnownAndUnknownAddresses() throws Exception {
        UUID tenant = fixture.createTenant("reset-enumerate");
        fixture.createUserWithPassword(tenant, "known@example.com", "the-old-password");

        for (String email : new String[]{"known@example.com", "no-such-address@example.com"}) {
            mvc.perform(post("/api/t/reset-enumerate/auth/password-reset/request")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("email", email))))
                    .andExpect(status().isNoContent());
        }
    }

    /**
     * Not in the plan. request() returns the raw token so tests and the dev email
     * sender can use it; for an unknown address it must return empty rather than
     * inventing one, and must not create a row.
     */
    @Test
    void requestForAnUnknownAddressCreatesNothing() {
        UUID tenant = fixture.createTenant("reset-unknown");

        fixture.runAs(tenant, () ->
                assertThat(resets.request("nobody@example.com")).isEmpty());
    }
}
