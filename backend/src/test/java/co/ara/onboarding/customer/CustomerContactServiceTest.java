package co.ara.onboarding.customer;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The plan has no contact tests at all, despite CustomerContactService being half
 * the task and the only place the "no derived finders" rule actually binds.
 */
class CustomerContactServiceTest extends PostgresTestBase {

    @Autowired CustomerContactService contacts;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    @Test
    void listReturnsContactsOfTheCustomer() {
        UUID tenant = fixture.createTenant("contact-list");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "lister@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Listed Ltd", user.get(), null, null));
            UUID otherCustomer = fixture.createCustomer(tenant, "Other Ltd", user.get(), null, null);
            fixture.createContact(tenant, customerId.get(), "in@example.com");
            fixture.createContact(tenant, otherCustomer, "out@example.com");

            UUID role = roles.createRole("Contact Viewer", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.list(customerId.get()))
                    .extracting(CustomerContactService.ContactView::email)
                    .containsExactly("in@example.com"));
    }

    /**
     * The scope predicate for a contact resolves through its parent customer, so a
     * contact belonging to someone else's customer must be invisible even though the
     * contact row itself carries no owner.
     */
    @Test
    void contactsInheritScopeFromTheirCustomer() {
        UUID tenant = fixture.createTenant("contact-scope");
        var user = new AtomicReference<UUID>();
        var theirCustomer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "narrow@example.com"));
            UUID other = fixture.createUser(tenant, "someone@example.com");
            UUID mine = fixture.createCustomer(tenant, "Mine Ltd", user.get(), null, null);
            theirCustomer.set(fixture.createCustomer(tenant, "Theirs Ltd", other, null, null));
            fixture.createContact(tenant, mine, "mine@example.com");
            fixture.createContact(tenant, theirCustomer.get(), "theirs@example.com");

            UUID role = roles.createRole("Assigned Contacts", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.list(theirCustomer.get()))
                    .as("a contact under another owner's customer is out of scope")
                    .isEmpty());
    }

    @Test
    void creatingAContactRequiresContactManage() {
        UUID tenant = fixture.createTenant("contact-gated");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "viewer@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Gated Ltd", user.get(), null, null));
            // CONTACT_VIEW only, deliberately not CONTACT_MANAGE.
            UUID role = roles.createRole("Read Only", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(customerId.get(), new CustomerContactService.CreateContactRequest(
                        "New Person", "new@example.com", null, null, false))))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * sendInvitation reaches auth through a port declared in this module, because
     * auth already depends on customer — a direct call would close a cycle. This
     * proves the wiring works end to end and that the returned token is real.
     */
    @Test
    void sendInvitationIssuesAnActivationToken() {
        UUID tenant = fixture.createTenant("contact-invite");
        var user = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "inviter@example.com"));
            UUID customerId = fixture.createCustomer(tenant, "Invite Ltd", user.get(), null, null);
            contactId.set(fixture.createContact(tenant, customerId, "invitee@example.com"));
            UUID role = roles.createRole("Inviter", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL,
                    PermissionKeys.INVITATION_SEND, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.sendInvitation(contactId.get()))
                    .as("the raw activation token, returned once")
                    .isNotBlank());
    }

    /**
     * A WRITE must not reach a parent the actor cannot read.
     *
     * create takes a customerId straight from the URL, and @RequirePermission is
     * coarse by design -- PermissionGateAspect answers only "does this user hold
     * the permission at ANY scope". So without a parent lookup a narrowly scoped
     * contact.manage holder could attach a contact to any customer in the tenant,
     * including ones invisible to them. Reads were never affected, which is what
     * made it quiet: they could not see or invite what they had written.
     *
     * NoSuchElementException, not AccessDeniedException: an unreachable parent is
     * indistinguishable from one that does not exist, and 403 here would confirm
     * the customer is real to someone who cannot see it.
     */
    @Test
    void creatingAContactUnderAnOutOfScopeCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("contact-parent-scope");
        var user = new AtomicReference<UUID>();
        var theirCustomer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "narrow@example.com"));
            UUID someoneElse = fixture.createUser(tenant, "owner@example.com");
            theirCustomer.set(fixture.createCustomer(tenant, "Theirs Ltd", someoneElse, null, null));
            UUID role = roles.createRole("Assigned Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(theirCustomer.get(), new CustomerContactService.CreateContactRequest(
                        "Smuggled", "smuggled@example.com", null, null, false))))
                .as("a contact written under a customer the actor cannot see")
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * The other half, and the one that stops the guard above passing for the wrong
     * reason. A parent check that denied everything would satisfy the negative test
     * perfectly while breaking the feature, so the same narrowly scoped actor must
     * still be able to write under a customer that IS theirs.
     */
    @Test
    void creatingAContactUnderAnInScopeCustomerStillWorks() {
        UUID tenant = fixture.createTenant("contact-parent-allowed");
        var user = new AtomicReference<UUID>();
        var myCustomer = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "mine@example.com"));
            myCustomer.set(fixture.createCustomer(tenant, "Mine Ltd", user.get(), null, null));
            UUID role = roles.createRole("Assigned Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.create(myCustomer.get(), new CustomerContactService.CreateContactRequest(
                    "Legitimate", "legit@example.com", null, null, false)).email())
                    .isEqualTo("legit@example.com"));
    }

    /**
     * update was already covered and this proves it rather than assuming it.
     *
     * It reads the contact through AuthorizedQuery, and CustomerContactDescriptor
     * resolves every contact scope through a subquery over its parent customer, so
     * an out-of-scope parent already makes the contact itself unreachable. The
     * request carries no customerId either, so a contact cannot be moved under a
     * different parent. Expected to pass on its first run; kept because "already
     * covered" is a claim that should be executable.
     */
    @Test
    void updatingAContactUnderAnOutOfScopeCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("contact-update-scope");
        var user = new AtomicReference<UUID>();
        var theirContact = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "narrow-edit@example.com"));
            UUID someoneElse = fixture.createUser(tenant, "owner-edit@example.com");
            UUID theirCustomer = fixture.createCustomer(tenant, "Theirs Ltd", someoneElse, null, null);
            theirContact.set(fixture.createContact(tenant, theirCustomer, "theirs@example.com"));
            UUID role = roles.createRole("Assigned Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.update(theirContact.get(), new CustomerContactService.UpdateContactRequest(
                        "Hijacked", "hijacked@example.com", null, null, false, ContactStatus.ACTIVE))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * And the invitation path, which is what would have turned write-side pollution
     * into something worse. InvitationService.issue is separately gated on
     * INVITATION_SEND and reads the contact through AuthorizedQuery, so a contact
     * under an unreachable customer cannot be invited either — the bypass never
     * chained. Also expected to pass first time.
     */
    @Test
    void invitingAContactUnderAnOutOfScopeCustomerIsNotFound() {
        UUID tenant = fixture.createTenant("contact-invite-scope");
        var user = new AtomicReference<UUID>();
        var theirContact = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "narrow-invite@example.com"));
            UUID someoneElse = fixture.createUser(tenant, "owner-invite@example.com");
            UUID theirCustomer = fixture.createCustomer(tenant, "Theirs Ltd", someoneElse, null, null);
            theirContact.set(fixture.createContact(tenant, theirCustomer, "theirs-invite@example.com"));
            UUID role = roles.createRole("Assigned Inviter", "", Map.of(
                    PermissionKeys.INVITATION_SEND, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.sendInvitation(theirContact.get())))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * The retirement path, and the only one a contact has.
     *
     * DELETE is deny-by-default at the database layer and business records are
     * deactivated rather than deleted, which makes status = INACTIVE the whole of
     * a contact's retirement. That the update endpoint accepts it is therefore
     * load-bearing, not incidental -- without it a contact once created could
     * never be retired by any means the product offers. Pinned because the edit
     * UI added in Task R2 depends on it.
     */
    @Test
    void updateCanCorrectAContactAndRetireIt() {
        UUID tenant = fixture.createTenant("contact-retire");
        var user = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "editor@example.com"));
            UUID customerId = fixture.createCustomer(tenant, "Retire Ltd", user.get(), null, null);
            contactId.set(fixture.createContact(tenant, customerId, "typo@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var updated = contacts.update(contactId.get(), new CustomerContactService.UpdateContactRequest(
                    "Corrected Name", "correct@example.com", "Head of Ops", "+44 20 7946 0000",
                    true, ContactStatus.INACTIVE));

            assertThat(updated.email()).isEqualTo("correct@example.com");
            assertThat(updated.fullName()).isEqualTo("Corrected Name");
            assertThat(updated.status())
                    .as("INACTIVE is the only retirement a contact has")
                    .isEqualTo(ContactStatus.INACTIVE);
        });
    }

    /**
     * UNIQUE (customer_id, email) on customer_contact (V8) is a foreseeable user
     * error -- two people entering the same address -- and it surfaced as a bare
     * 500 that told the caller nothing about what was wrong. A constraint the
     * product can predict deserves a client error, so it is translated to a
     * domain exception the customer module maps to 409.
     *
     * This test is also the guard on the constraint NAME the translation matches:
     * rename it in a later migration without updating the service and this goes
     * red rather than quietly returning 500 again.
     */
    @Test
    void asecondContactWithTheSameEmailOnOneCustomerIsAConflict() {
        UUID tenant = fixture.createTenant("contact-duplicate");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "dupe@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Duplicate Ltd", user.get(), null, null));
            fixture.createContact(tenant, customerId.get(), "taken@example.com");
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(customerId.get(), new CustomerContactService.CreateContactRequest(
                        "Second Person", "taken@example.com", null, null, false))))
                .isInstanceOf(DuplicateContactEmailException.class);
    }

    /** The same constraint, reached by editing rather than by creating. */
    @Test
    void updatingAContactOntoAnAddressAlreadyUsedOnThatCustomerIsAConflict() {
        UUID tenant = fixture.createTenant("contact-dupe-edit");
        var user = new AtomicReference<UUID>();
        var second = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "dupe-edit@example.com"));
            UUID customerId = fixture.createCustomer(tenant, "Dupe Edit Ltd", user.get(), null, null);
            fixture.createContact(tenant, customerId, "first@example.com");
            second.set(fixture.createContact(tenant, customerId, "second@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.update(second.get(), new CustomerContactService.UpdateContactRequest(
                        "Second Person", "first@example.com", null, null, false, ContactStatus.ACTIVE))))
                .isInstanceOf(DuplicateContactEmailException.class);
    }

    /**
     * A different constraint must NOT be reported as a duplicate address. A
     * contact hung off a customer id that does not exist violates the foreign
     * key, and translating every DataIntegrityViolationException into "that email
     * is taken" would send the caller looking for a duplicate that is not there.
     */
    @Test
    void anUnrelatedConstraintViolationIsNotReportedAsADuplicateEmail() {
        UUID tenant = fixture.createTenant("contact-fk");
        var user = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "fk@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(UUID.randomUUID(), new CustomerContactService.CreateContactRequest(
                        "Orphan", "orphan@example.com", null, null, false))))
                .isNotInstanceOf(DuplicateContactEmailException.class);
    }

    @Test
    void sendInvitationRequiresTheInvitationPermission() {
        UUID tenant = fixture.createTenant("contact-invite-denied");
        var user = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "noinvite@example.com"));
            UUID customerId = fixture.createCustomer(tenant, "Denied Ltd", user.get(), null, null);
            contactId.set(fixture.createContact(tenant, customerId, "nope@example.com"));
            UUID role = roles.createRole("No Invite", "", Map.of(
                    PermissionKeys.CONTACT_VIEW, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.sendInvitation(contactId.get())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
