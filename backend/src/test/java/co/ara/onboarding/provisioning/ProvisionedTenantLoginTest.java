package co.ara.onboarding.provisioning;

import co.ara.onboarding.auth.InvitationPurpose;
import co.ara.onboarding.auth.InvitationRepository;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.RecordingEmailSender;
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
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one path nothing else in the suite drives: provision a tenant, then sign in
 * as the administrator it created.
 *
 * Every other test constructs its users directly, which is why a total dead end
 * survived twenty-seven tasks. The administrator is created INVITED with no
 * password hash; LoginService admits only ACTIVE; only ActivationService.accept
 * promotes INVITED -> ACTIVE, and it demands an ACTIVATION invitation.
 * UserInvitationService.issueForUser would create one but is gated on user.manage,
 * which nobody holds until this account works — so provisioning has to issue it.
 *
 * Driven over HTTP rather than through the services, because the reproduction that
 * found this was a sequence of curl calls and the fix is only worth anything if
 * that sequence now works.
 */
@AutoConfigureMockMvc
class ProvisionedTenantLoginTest extends PostgresTestBase {

    private static final String SLUG = "bootstrap";
    private static final String ADMIN = "admin@bootstrap.example";
    private static final String PASSWORD = "Str0ng-Passw0rd!";

    @Autowired TenantProvisioningService provisioning;
    @Autowired RecordingEmailSender emails;
    @Autowired AppUserRepository users;
    @Autowired InvitationRepository invitations;
    @Autowired TenantFixture fixture;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void aProvisionedAdministratorCanActivateAndSignIn() throws Exception {
        UUID tenantId = provisioning.provision(SLUG, "Bootstrap Co", ADMIN, "Bootstrap Admin");

        // 1. Provisioning must hand the administrator a way in. The invitation token
        //    travels by email and nowhere else -- it is never in the provisioning
        //    response, because it is a SecureRandom secret like every other one here.
        String token = emails.tokenTo(ADMIN).orElseThrow(() -> new AssertionError(
                "provisioning must email the administrator an ACTIVATION token; without one "
                        + "the account can never leave INVITED and login always answers 401"));

        // 2. Redeeming it must promote INVITED -> ACTIVE. This is the check login
        //    fails at: PasswordResetService sets a hash but deliberately never
        //    touches status, so a reset is not a substitute for activation.
        mvc.perform(post("/api/t/" + SLUG + "/auth/activate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("token", token, "password", PASSWORD))))
           .andExpect(status().isNoContent());

        fixture.runUnauthenticated(tenantId, () ->
                assertThat(users.findByTenantIdAndEmailIgnoreCase(tenantId, ADMIN).orElseThrow()
                        .getStatus())
                        .as("activation must promote the provisioned administrator to ACTIVE")
                        .isEqualTo(UserStatus.ACTIVE));

        // 3. And the account must then be able to sign in.
        String body = mvc.perform(post("/api/t/" + SLUG + "/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                            Map.of("email", ADMIN, "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        // 4. Usable, not merely issued: the session must carry the seeded
        //    Administrator role's authority, which is what makes the tenant
        //    administrable at all.
        String accessToken = json.readTree(body).get("accessToken").asText();
        mvc.perform(get("/api/t/" + SLUG + "/me")
                    .header("Authorization", "Bearer " + accessToken))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.permissions['user.manage']", hasItem("ALL")))
           .andExpect(jsonPath("$.permissions['role.manage']", hasItem("ALL")));
    }

    /**
     * The invitation is a bearer credential, so it must be stored the way every
     * other one in this system is: a SHA-256 hash of 256 bits of SecureRandom, with
     * the raw value existing only in the email. A row holding the token itself
     * would turn a database read into a set of working credentials.
     */
    @Test
    void theActivationInvitationIsStoredAsAHashAndTargetsTheAdministrator() {
        UUID tenantId = provisioning.provision(
                "hashed-invite", "Hashed Co", "admin@hashed.example", "Hashed Admin");

        String raw = emails.tokenTo("admin@hashed.example").orElseThrow(() -> new AssertionError(
                "provisioning must email the administrator an ACTIVATION token"));

        fixture.runUnauthenticated(tenantId, () -> {
            UUID adminId = users.findByTenantIdAndEmailIgnoreCase(tenantId, "admin@hashed.example")
                    .orElseThrow().getId();
            var invitation = invitations.findAll().stream()
                    .filter(i -> adminId.equals(i.getUserId()))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "no invitation row exists for the provisioned administrator"));

            assertThat(invitation.getPurpose()).isEqualTo(InvitationPurpose.ACTIVATION);
            assertThat(invitation.getExpiresAt()).isAfter(Instant.now());
            assertThat(invitation.getTokenHash())
                    .as("the raw token must never be persisted")
                    .isNotEqualTo(raw)
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        });
    }
}
