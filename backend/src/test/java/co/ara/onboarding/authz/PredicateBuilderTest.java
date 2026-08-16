package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PredicateBuilderTest extends PostgresTestBase {

    @Autowired AuthorizationPredicateBuilder predicates;
    @Autowired CustomerRepository customers;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    /**
     * The important test in this class: it proves scopes are SETS, not a hierarchy.
     * The record is owned by the user but belongs to a team the user is not in, so
     * an implementation that treated TEAM as subsuming ASSIGNED, or that resolved
     * ASSIGNED through team membership, would miss it.
     */
    @Test
    void assignedScopeSeesPersonallyOwnedRecordOutsideOwnTeam() {
        UUID tenant = fixture.createTenant("assigned-scope");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "owner@example.com"));
            UUID otherTeam = fixture.createTeam(tenant, "Other Team");

            // Owned by this user, but belonging to a team the user is NOT in.
            fixture.createCustomer(tenant, "Mine", user.get(), null, otherTeam);
            fixture.createCustomer(tenant, "Not Mine", null, null, otherTeam);

            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec))
                    .extracting(Customer::getDisplayName)
                    .containsExactly("Mine");
        });
    }

    @Test
    void noGrantMatchesNothing() {
        UUID tenant = fixture.createTenant("no-grant-predicate");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "nobody@example.com"));
            fixture.createCustomer(tenant, "Hidden", null, null, null);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec))
                    .as("absence of a grant is the denial (spec 6.5)")
                    .isEmpty();
        });
    }

    @Test
    void allScopeMatchesEverythingInTenant() {
        UUID tenant = fixture.createTenant("all-scope");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "admin@example.com"));
            fixture.createCustomer(tenant, "One", null, null, null);
            fixture.createCustomer(tenant, "Two", null, null, null);
            UUID role = roles.createRole("All Access", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec)).hasSize(2);
        });
    }

    /**
     * Not in the plan. Two grants at different record scopes must union, and the
     * single-scope tests above cannot distinguish a correct union from an
     * implementation that keeps only the last scope it iterated — with one scope
     * each, both behave identically. Ownership and team membership are deliberately
     * on different records so the union returns strictly more than either scope
     * alone.
     */
    @Test
    void twoRecordScopesUnionRatherThanOverride() {
        UUID tenant = fixture.createTenant("union-predicate");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "both@example.com"));
            UUID myTeam = fixture.createTeam(tenant, "My Team");
            fixture.addToTeam(tenant, user.get(), myTeam);

            fixture.createCustomer(tenant, "Owned", user.get(), null, null);
            fixture.createCustomer(tenant, "TeamOwned", null, null, myTeam);
            fixture.createCustomer(tenant, "Neither", null, null, null);

            UUID role = roles.createRole("Assigned Plus Team", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            UUID teamRole = roles.createRole("Team Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(user.get(), role);
            roles.assignRole(user.get(), teamRole);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var spec = predicates.forPermission(PermissionKeys.CUSTOMER_VIEW, Customer.class);
            assertThat(customers.findAll(spec))
                    .extracting(Customer::getDisplayName)
                    .containsExactlyInAnyOrder("Owned", "TeamOwned");
        });
    }
}
