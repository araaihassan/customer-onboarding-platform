package co.ara.onboarding.authz;

import co.ara.onboarding.customer.Customer;
import co.ara.onboarding.customer.CustomerRepository;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exception assertions wrap runAsUser rather than sitting inside its lambda:
 * runAsUser executes in a TransactionTemplate, so catching inside would leave the
 * transaction rollback-only and surface UnexpectedRollbackException instead of the
 * exception under test. See Global Constraints.
 */
class PermissionGateTest extends PostgresTestBase {

    @Autowired RoleService roles;
    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired CustomerRepository customers;
    @Autowired TenantFixture fixture;

    @Test
    void gateRejectsUserWithoutThePermission() {
        UUID tenant = fixture.createTenant("gate-deny");
        var user = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> user.set(fixture.createUser(tenant, "ungranted@example.com")));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> roles.createRole("Nope", "", Map.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void outOfScopeRecordIsNotFoundRatherThanForbidden() {
        UUID tenant = fixture.createTenant("scope-404");
        var user = new AtomicReference<UUID>();
        var hidden = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "scoped@example.com"));
            UUID otherUser = fixture.createUser(tenant, "other@example.com");
            hidden.set(fixture.createCustomer(tenant, "Someone Else's", otherUser, null, null));
            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> authorizedQuery.getById(
                        customers, Customer.class, PermissionKeys.CUSTOMER_VIEW, hidden.get())))
                .as("must not reveal that the record exists (spec 6.8)")
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void authorizedListReturnsOnlyInScopeRecords() {
        UUID tenant = fixture.createTenant("scoped-list");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "lister@example.com"));
            fixture.createCustomer(tenant, "Mine", user.get(), null, null);
            fixture.createCustomer(tenant, "Theirs", null, null, null);
            UUID role = roles.createRole("Assigned Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(authorizedQuery.findAll(customers, Customer.class,
                    PermissionKeys.CUSTOMER_VIEW, null, Pageable.unpaged()))
                .extracting(Customer::getDisplayName)
                .containsExactly("Mine"));
    }

    /**
     * Not in the plan, and the gate's most important negative case. The gate asks
     * only "does the user hold this permission at any scope"; it must not be
     * satisfied by holding some OTHER permission. A gate that checked "has any
     * grant at all" would pass every test above, since those users hold either
     * nothing or exactly the permission under test.
     */
    @Test
    void gateIsNotSatisfiedByADifferentPermission() {
        UUID tenant = fixture.createTenant("gate-wrong-perm");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "viewer@example.com"));
            // CUSTOMER_VIEW, but createRole is gated on ROLE_MANAGE.
            UUID role = roles.createRole("Viewer Only", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> roles.createRole("Should Fail", "", Map.of())))
                .as("holding customer.view must not satisfy a role.manage gate")
                .isInstanceOf(AccessDeniedException.class);
    }
}
