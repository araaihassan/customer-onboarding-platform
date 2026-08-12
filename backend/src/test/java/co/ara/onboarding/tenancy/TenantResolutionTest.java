package co.ara.onboarding.tenancy;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = true)
class TenantResolutionTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired TenantRepository tenants;

    @BeforeEach
    void seedTenant() {
        if (tenants.findBySlug("acme").isEmpty()) {
            Tenant t = new Tenant();
            t.setId(Uuid7.generate());
            t.setSlug("acme");
            t.setName("Acme Corp");
            t.setStatus(TenantStatus.ACTIVE);
            tenants.save(t);
        }
    }

    @Test
    void bindsTenantFromPathPrefix() throws Exception {
        mvc.perform(get("/api/t/acme/_debug/tenant"))
           .andExpect(status().isOk())
           .andExpect(content().string("acme"));
    }

    @Test
    void unknownTenantSlugReturns404() throws Exception {
        mvc.perform(get("/api/t/does-not-exist/_debug/tenant"))
           .andExpect(status().isNotFound());
    }

    @Test
    void suspendedTenantReturns404() throws Exception {
        Tenant t = new Tenant();
        t.setId(Uuid7.generate());
        t.setSlug("suspended-co");
        t.setName("Suspended Co");
        t.setStatus(TenantStatus.SUSPENDED);
        tenants.save(t);

        mvc.perform(get("/api/t/suspended-co/_debug/tenant"))
           .andExpect(status().isNotFound());
    }

    @Test
    void requestScopedTransactionSetsAppTenantIdInPostgres() throws Exception {
        mvc.perform(get("/api/t/acme/_debug/tenant-setting"))
           .andExpect(status().isOk())
           .andExpect(content().string(
                   tenants.findBySlug("acme").orElseThrow().getId().toString()));
    }
}
