package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.customer.CustomerStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DescriptorRegistryTest extends PostgresTestBase {

    @Autowired DescriptorRegistry registry;
    @Autowired CustomerRepository customers;
    @Autowired TenantFixture fixture;

    @Test
    void everyRecordScopedPermissionHasADescriptor() {
        // Passes only because all four descriptors are registered; this is the
        // same assertion the application makes at startup.
        registry.validate();
    }

    @Test
    void missingDescriptorIsAStartupFailure() {
        DescriptorRegistry empty = new DescriptorRegistry(List.of());
        assertThatThrownBy(empty::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no ResourceAuthorizationDescriptor");
    }

    @Test
    void customerDeclaresOwnerOnlyForAssignedScope() {
        var descriptor = registry.forEntity(Customer.class);
        assertThat(descriptor.assignedRelationships())
                .containsExactly(RelationshipType.OWNER);
    }

    /**
     * Not in the plan. The descriptors' central safety claim is that an actor with
     * no department matches nothing rather than everything — fail closed, never
     * open — and nothing exercised the Specifications at all, so a departmentScope
     * that returned a conjunction (match-all) instead of a disjunction would have
     * passed every test above.
     *
     * The positive half matters just as much: a predicate that matches nothing
     * unconditionally would also satisfy the fail-closed assertion on its own.
     */
    @Test
    void departmentScopeMatchesTheDepartmentAndFailsClosedWithoutOne() {
        UUID tenant = fixture.createTenant("dept-scope");

        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@dept-scope.example");
            // A real department row: customer.owning_department_id is a foreign key, so
            // a synthetic UUID fails the constraint rather than the assertion.
            UUID department = fixture.createDepartment(tenant, "Onboarding");

            customers.save(customer(tenant, "In Department", owner, department));
            customers.save(customer(tenant, "No Department", owner, null));

            var descriptor = registry.forEntity(Customer.class);

            var inDepartment = descriptor.departmentScope(
                    new AuthContext(tenant, owner, UserType.INTERNAL, department, Set.of()));
            assertThat(customers.findAll(inDepartment))
                    .as("an actor in the department sees that department's records")
                    .extracting(Customer::getDisplayName)
                    .containsExactly("In Department");

            var noDepartment = descriptor.departmentScope(
                    new AuthContext(tenant, owner, UserType.INTERNAL, null, Set.of()));
            assertThat(customers.findAll(noDepartment))
                    .as("an actor with no department must match nothing, not everything")
                    .isEmpty();
        });
    }

    private static Customer customer(UUID tenant, String name, UUID owner, UUID department) {
        Customer c = new Customer();
        c.setId(Uuid7.generate());
        c.setTenantId(tenant);
        c.setLegalName(name + " Ltd");
        c.setDisplayName(name);
        c.setStatus(CustomerStatus.PROSPECT);
        c.setOwnerUserId(owner);
        c.setCreatedBy(owner);
        c.setOwningDepartmentId(department);
        return c;
    }
}
