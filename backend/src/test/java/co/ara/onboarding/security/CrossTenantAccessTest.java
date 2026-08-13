package co.ara.onboarding.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test 1 — cross-tenant access. */
class CrossTenantAccessTest extends SecurityTestBase {

    @Test
    void tokenFromTenantACannotBeUsedAgainstTenantB() throws Exception {
        UUID tenantA = fixture.createTenant("sec-tenant-a");
        fixture.createTenant("sec-tenant-b");
        var userA = fixture.createAdminUser(tenantA, "a@example.com");

        mvc.perform(as(get("/api/t/sec-tenant-b/customers"), userA))
           .andExpect(status().isUnauthorized());
    }

    /**
     * 404, not 403. The intruder is a legitimate administrator of their own tenant,
     * so this is the case where a system could plausibly answer "forbidden" and leak
     * that the id exists somewhere.
     */
    @Test
    void customerOfAnotherTenantIsNotReachableByDirectId() throws Exception {
        UUID tenantA = fixture.createTenant("sec-owner");
        UUID tenantB = fixture.createTenant("sec-intruder");
        fixture.createAdminUser(tenantA, "owner@example.com");
        var adminB = fixture.createAdminUser(tenantB, "intruder@example.com");
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenantA, () ->
            customerId.set(fixture.createCustomer(tenantA, "Tenant A Customer", null, null, null)));

        mvc.perform(as(get("/api/t/sec-intruder/customers/" + customerId.get()), adminB))
           .andExpect(status().isNotFound());
    }

    @Test
    void listingNeverLeaksAnotherTenantsRows() throws Exception {
        UUID tenantA = fixture.createTenant("sec-list-a");
        UUID tenantB = fixture.createTenant("sec-list-b");
        var adminB = fixture.createAdminUser(tenantB, "listb@example.com");

        fixture.runAs(tenantA, () -> fixture.createCustomer(tenantA, "Secret A", null, null, null));

        mvc.perform(as(get("/api/t/sec-list-b/customers"), adminB))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content").isEmpty());
    }

    /**
     * Not in the plan. Tenant B's administrator holds full authority in their own
     * tenant, so an empty list above could equally mean "isolation works" or "this
     * admin can see nothing at all". Creating a customer in B and seeing exactly it
     * distinguishes the two.
     */
    @Test
    void theSameAdministratorStillSeesTheirOwnTenantsRows() throws Exception {
        UUID tenantA = fixture.createTenant("sec-both-a");
        UUID tenantB = fixture.createTenant("sec-both-b");
        var adminB = fixture.createAdminUser(tenantB, "bothb@example.com");

        fixture.runAs(tenantA, () -> fixture.createCustomer(tenantA, "Secret A", null, null, null));
        fixture.runAs(tenantB, () -> fixture.createCustomer(tenantB, "Visible B", null, null, null));

        mvc.perform(as(get("/api/t/sec-both-b/customers"), adminB))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.content.length()").value(1))
           .andExpect(jsonPath("$.content[0].displayName").value("Visible B"));
    }
}
