package co.ara.onboarding.customer;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
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

class CustomerServiceTest extends PostgresTestBase {

    @Autowired CustomerService customers;
    @Autowired RoleService roles;
    @Autowired AuditEventRepository auditEvents;
    @Autowired TenantFixture fixture;

    @Test
    void createRequiresTheCreatePermission() {
        UUID tenant = fixture.createTenant("cust-create-denied");
        var user = new AtomicReference<UUID>();
        fixture.runAs(tenant, () -> user.set(fixture.createUser(tenant, "nocreate@example.com")));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(), () -> customers.create(
                new CustomerService.CreateCustomerRequest(
                        "Denied Ltd", "Denied", null, null, null, null, null))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void listReturnsOnlyRecordsInScope() {
        UUID tenant = fixture.createTenant("cust-list-scope");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "scoped@example.com"));
            fixture.createCustomer(tenant, "Mine", user.get(), null, null);
            fixture.createCustomer(tenant, "Theirs", null, null, null);
            UUID role = roles.createRole("Assigned Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(customers.list(null, null, Pageable.unpaged()))
                    .extracting(CustomerService.CustomerView::displayName)
                    .containsExactly("Mine"));
    }

    @Test
    void deactivationSetsStatusAndWritesAudit() {
        UUID tenant = fixture.createTenant("cust-deactivate");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "deact@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Closing Ltd", user.get(), null, null));
            UUID role = roles.createRole("Deactivator", "", Map.of(
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_DEACTIVATE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            customers.deactivate(customerId.get(), "Contract ended");
            assertThat(customers.get(customerId.get()).status()).isEqualTo(CustomerStatus.INACTIVE);
        });

        // The test name claims an audit event; assert it rather than only the status.
        // Deactivation is the closest thing to a delete this system has, so its
        // audit trail is the only record that it happened.
        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("customer.deactivated:" + customerId.get()));
    }

    @Test
    void getOutOfScopeCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("cust-get-404");
        var user = new AtomicReference<UUID>();
        var hidden = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "limited@example.com"));
            UUID other = fixture.createUser(tenant, "otherowner@example.com");
            hidden.set(fixture.createCustomer(tenant, "Hidden", other, null, null));
            UUID role = roles.createRole("Assigned Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> customers.get(hidden.get())))
                .as("out of scope must be indistinguishable from absent (spec 6.8)")
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Not in the plan, and the privilege-escalation case the service's own comment
     * warns about. A user who may VIEW a record at ALL scope but EDIT only what they
     * own must not be able to edit someone else's — which is only true because
     * update() fetches with CUSTOMER_EDIT rather than CUSTOMER_VIEW. Fetching with
     * the read permission and then writing would pass every other test here.
     */
    @Test
    void editingUsesTheWritePermissionNotTheReadOne() {
        UUID tenant = fixture.createTenant("cust-edit-scope");
        var user = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "editor@example.com"));
            UUID other = fixture.createUser(tenant, "owner@example.com");
            theirs.set(fixture.createCustomer(tenant, "Not Mine", other, null, null));
            UUID role = roles.createRole("Wide Read Narrow Write", "", Map.of(
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_EDIT, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        // Visible...
        fixture.runAsUser(tenant, user.get(), () ->
                assertThat(customers.get(theirs.get()).displayName()).isEqualTo("Not Mine"));

        // ...but not editable.
        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> customers.update(theirs.get(), new CustomerService.UpdateCustomerRequest(
                        "Hijacked Ltd", "Hijacked", null, null, null, user.get(), null, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    /**
     * Not in the plan. update() is a full replace, and a full replace is only
     * honest if the read returns everything the write accepts. externalRef was
     * writable on both request records but absent from CustomerView, so any client
     * that loaded a customer, changed one field and saved erased it — it cannot
     * round-trip a value it was never given.
     *
     * This is written as a client would behave: read, copy the view into an update
     * request, change one unrelated field, save. Nothing in the test knows the
     * external reference exists, which is the whole point — that is exactly the
     * position every real caller is in.
     */
    @Test
    void editingOneFieldPreservesTheExternalReference() {
        UUID tenant = fixture.createTenant("cust-external-ref");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "roundtrip@example.com"));
            UUID role = roles.createRole("Editor", "", Map.of(
                    PermissionKeys.CUSTOMER_CREATE, Scope.ALL,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ALL,
                    PermissionKeys.CUSTOMER_EDIT, Scope.ALL,
                    PermissionKeys.USER_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
                customerId.set(customers.create(new CustomerService.CreateCustomerRequest(
                        "Ledger Ltd", "Ledger", "Finance", "GB",
                        "ERP-4471", null, null)).id()));

        fixture.runAsUser(tenant, user.get(), () -> {
            var loaded = customers.get(customerId.get());

            customers.update(customerId.get(), new CustomerService.UpdateCustomerRequest(
                    loaded.legalName(), "Ledger (renamed)", loaded.industry(),
                    loaded.country(), loaded.externalRef(), loaded.ownerUserId(),
                    loaded.owningDepartmentId(), loaded.owningTeamId()));
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var after = customers.get(customerId.get());
            assertThat(after.displayName()).isEqualTo("Ledger (renamed)");
            assertThat(after.externalRef())
                    .as("a full-replace update must not erase a field the read never returned")
                    .isEqualTo("ERP-4471");
        });
    }

    /**
     * Not in the plan. The creator becoming the owner is what makes ASSIGNED scope
     * usable straight after creation — without it a user with CUSTOMER_CREATE and
     * CUSTOMER_VIEW at ASSIGNED would create records they immediately cannot see.
     */
    @Test
    void creatorBecomesTheOwnerSoAssignedScopeSeesItImmediately() {
        UUID tenant = fixture.createTenant("cust-create-owner");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "creator@example.com"));
            UUID role = roles.createRole("Creator", "", Map.of(
                    PermissionKeys.CUSTOMER_CREATE, Scope.ALL,
                    PermissionKeys.CUSTOMER_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var created = customers.create(new CustomerService.CreateCustomerRequest(
                    "Fresh Ltd", "Fresh", null, null, null, null, null));

            assertThat(created.ownerUserId()).isEqualTo(user.get());
            assertThat(customers.list(null, null, Pageable.unpaged()))
                    .as("and it is visible at ASSIGNED scope right away")
                    .extracting(CustomerService.CustomerView::displayName)
                    .containsExactly("Fresh");
        });
    }
}
