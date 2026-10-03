package co.ara.onboarding.identity;

import co.ara.onboarding.security.SecurityTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec 8: a user naming themselves as their own manager is a 422 over HTTP, not a 400. */
class SelfManagerHttpTest extends SecurityTestBase {

    @Test
    void namingYourselfAsManagerIs422WithAProblemBody() throws Exception {
        UUID tenant = fixture.createTenant("self-mgr-http");
        var admin = fixture.createAdminUser(tenant, "admin@self-mgr-http.test");
        UUID u = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "u@self-mgr-http.test"));
        mvc.perform(as(put("/api/t/self-mgr-http/admin/users/" + u), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"U\",\"managerId\":\"" + u + "\"}"))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.status").value(422))
           .andExpect(jsonPath("$.detail").value("A user cannot be their own manager"));
    }

    @Test
    void anUnknownManagerIsStill404() throws Exception {
        UUID tenant = fixture.createTenant("self-mgr-404");
        var admin = fixture.createAdminUser(tenant, "admin@self-mgr-404.test");
        UUID u = fixture.runAsReturning(tenant, () -> fixture.createUser(tenant, "u@self-mgr-404.test"));
        mvc.perform(as(put("/api/t/self-mgr-404/admin/users/" + u), admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"U\",\"managerId\":\"" + UUID.randomUUID() + "\"}"))
           .andExpect(status().isNotFound());
    }
}
