package co.ara.onboarding.security;

import co.ara.onboarding.identity.AppUser;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test 2 — authenticated, but holding no permission at all. */
class InsufficientPermissionTest extends SecurityTestBase {

    /** ACTIVE and INTERNAL, so the only thing missing is authority. */
    private AppUser userWithNoRoles(String slug) {
        UUID tenant = fixture.createTenant(slug);
        return fixture.createUserWithPassword(tenant, "nobody@example.com", "long-enough-password");
    }

    @Test
    void creatingACustomerIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-create");
        mvc.perform(as(post("/api/t/perm-create/customers"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
           .andExpect(status().isForbidden());
    }

    @Test
    void listingUsersIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-users");
        mvc.perform(as(get("/api/t/perm-users/admin/users"), user))
           .andExpect(status().isForbidden());
    }

    @Test
    void creatingARoleIsForbidden() throws Exception {
        var user = userWithNoRoles("perm-roles");
        mvc.perform(as(post("/api/t/perm-roles/admin/roles"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Nope\",\"description\":\"\",\"grants\":{}}"))
           .andExpect(status().isForbidden());
    }

    /**
     * The response must not teach a caller which permission would unlock the
     * endpoint. A body naming customer.create turns every 403 into a map of the
     * authorization model, which is exactly what an attacker enumerating an API
     * wants.
     */
    @Test
    void theResponseNamesNoPermission() throws Exception {
        var user = userWithNoRoles("perm-quiet");

        String body = mvc.perform(as(post("/api/t/perm-quiet/customers"), user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"legalName\":\"X Ltd\",\"displayName\":\"X\"}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("customer.create")
                .doesNotContain("customer.view")
                .doesNotContain("RequirePermission")
                .doesNotContain("permission");
    }
}
