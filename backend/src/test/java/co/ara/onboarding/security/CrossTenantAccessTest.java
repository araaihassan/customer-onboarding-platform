package co.ara.onboarding.security;

import co.ara.onboarding.customer.CustomerService;
import co.ara.onboarding.platform.Uuid7;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Test 1 — cross-tenant access. */
class CrossTenantAccessTest extends SecurityTestBase {

    @Autowired CustomerService customers;

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

    @Test
    void anotherTenantsDepartmentIdCannotBecomeACustomersOwningDepartment() {
        UUID tenantA = fixture.createTenant("own-a");
        UUID tenantB = fixture.createTenant("own-b");
        var strangerDepartmentId = new UUID[1];
        fixture.runAs(tenantB, () ->
                strangerDepartmentId[0] = fixture.createDepartment(tenantB, "B's Ops"));

        // A real id, but in another tenant. The FK is satisfied because RLS is
        // bypassed for referential integrity, so before the fix this answered 200.
        // create() never takes an ownerUserId (the actor becomes the owner), but
        // DOES take owningDepartmentId straight from the request -- that is the
        // field under attack here.
        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                customers.create(new CustomerService.CreateCustomerRequest(
                        "Acme", null, null, null, null, strangerDepartmentId[0], null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void anInventedDepartmentIdIsA404NotA500() {
        UUID tenant = fixture.createTenant("own-invented");
        assertThatThrownBy(() -> fixture.runAs(tenant, () ->
                customers.create(new CustomerService.CreateCustomerRequest(
                        "Acme", null, null, null, null, Uuid7.generate(), null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * update() is the sharper case: unlike create(), it takes ownerUserId
     * straight from the request (CustomerService.java:131), so a real user id
     * belonging to another tenant satisfies the FK and hands ownership of this
     * tenant's customer to a stranger who cannot even see it.
     */
    @Test
    void updateCannotHandOwnershipToAnotherTenantsUser() {
        UUID tenantA = fixture.createTenant("own-upd-a");
        UUID tenantB = fixture.createTenant("own-upd-b");
        var strangerUserId = new UUID[1];
        fixture.runAs(tenantB, () -> strangerUserId[0] = fixture.createUser(tenantB, "stranger@b.test"));

        var customerId = new UUID[1];
        fixture.runAs(tenantA, () -> customerId[0] = customers.create(new CustomerService.CreateCustomerRequest(
                "Acme Corp", "Acme", null, null, null, null, null)).id());

        assertThatThrownBy(() -> fixture.runAs(tenantA, () ->
                customers.update(customerId[0], new CustomerService.UpdateCustomerRequest(
                        "Acme Corp", "Acme", null, null, null, strangerUserId[0], null, null))))
                .isInstanceOf(NoSuchElementException.class);
    }
}
