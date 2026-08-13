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
