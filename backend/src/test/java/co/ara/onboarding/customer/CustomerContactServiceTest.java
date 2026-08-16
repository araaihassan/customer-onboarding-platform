package co.ara.onboarding.customer;

import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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
    @Autowired AuditEventRepository auditEvents;

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
     * DEPARTMENT and TEAM run through different descriptor branches from ASSIGNED,
     * so a parent check exercised only at ASSIGNED is a parent check whose other
     * paths are assumed. Each scope gets both directions, because a predicate that
     * denied everything would satisfy every negative case perfectly while breaking
     * the feature.
     */
    @Test
    void theParentCheckHoldsAtDepartmentScope() {
        UUID tenant = fixture.createTenant("contact-parent-dept");
        var user = new AtomicReference<UUID>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID myDepartment = fixture.createDepartment(tenant, "Onboarding");
            UUID otherDepartment = fixture.createDepartment(tenant, "Finance");
            user.set(fixture.createUserInDepartment(tenant, "dept@example.com", myDepartment));
            mine.set(fixture.createCustomer(tenant, "Ours Ltd", null, myDepartment, null));
            theirs.set(fixture.createCustomer(tenant, "Theirs Ltd", null, otherDepartment, null));
            UUID role = roles.createRole("Department Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.DEPARTMENT));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.create(mine.get(), new CustomerContactService.CreateContactRequest(
                    "In Department", "in-dept@example.com", null, null, false)).email())
                    .isEqualTo("in-dept@example.com"));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(theirs.get(), new CustomerContactService.CreateContactRequest(
                        "Other Department", "out-dept@example.com", null, null, false))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theParentCheckHoldsAtTeamScope() {
        UUID tenant = fixture.createTenant("contact-parent-team");
        var user = new AtomicReference<UUID>();
        var mine = new AtomicReference<UUID>();
        var theirs = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID myTeam = fixture.createTeam(tenant, "Pod A");
            UUID otherTeam = fixture.createTeam(tenant, "Pod B");
            user.set(fixture.createUser(tenant, "team@example.com"));
            fixture.addToTeam(tenant, user.get(), myTeam);
            mine.set(fixture.createCustomer(tenant, "Ours Ltd", null, null, myTeam));
            theirs.set(fixture.createCustomer(tenant, "Theirs Ltd", null, null, otherTeam));
            UUID role = roles.createRole("Team Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.TEAM));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            assertThat(contacts.create(mine.get(), new CustomerContactService.CreateContactRequest(
                    "In Team", "in-team@example.com", null, null, false)).email())
                    .isEqualTo("in-team@example.com"));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(theirs.get(), new CustomerContactService.CreateContactRequest(
                        "Other Team", "out-team@example.com", null, null, false))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * The fail-closed branches, which are the ones nobody writes by hand. A
     * DEPARTMENT-scoped actor with no department, and a TEAM-scoped actor in no
     * team, each resolve to cb.disjunction() — no rows, never all rows — so
     * neither can create anywhere. An inverted predicate would hand them the whole
     * tenant, and no other test in this file would notice.
     */
    @Test
    void anActorWithNoDepartmentOrTeamCanCreateNothing() {
        UUID tenant = fixture.createTenant("contact-parent-failclosed");
        var noDepartment = new AtomicReference<UUID>();
        var noTeam = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID department = fixture.createDepartment(tenant, "Onboarding");
            UUID team = fixture.createTeam(tenant, "Pod A");
            noDepartment.set(fixture.createUser(tenant, "no-dept@example.com"));
            noTeam.set(fixture.createUser(tenant, "no-team@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Owned Ltd", null, department, team));

            UUID departmentRole = roles.createRole("Department Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.DEPARTMENT));
            UUID teamRole = roles.createRole("Team Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.TEAM));
            roles.assignRole(noDepartment.get(), departmentRole);
            roles.assignRole(noTeam.get(), teamRole);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, noDepartment.get(),
                () -> contacts.create(customerId.get(), new CustomerContactService.CreateContactRequest(
                        "Departmentless", "no-dept-contact@example.com", null, null, false))))
                .as("a DEPARTMENT-scoped actor with no department reaches no customer")
                .isInstanceOf(NoSuchElementException.class);

        assertThatThrownBy(() -> fixture.runAsUser(tenant, noTeam.get(),
                () -> contacts.create(customerId.get(), new CustomerContactService.CreateContactRequest(
                        "Teamless", "no-team-contact@example.com", null, null, false))))
                .as("a TEAM-scoped actor in no team reaches no customer")
                .isInstanceOf(NoSuchElementException.class);
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
        var theirCustomer = new AtomicReference<UUID>();
        var theirContact = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "narrow-edit@example.com"));
            UUID someoneElse = fixture.createUser(tenant, "owner-edit@example.com");
            theirCustomer.set(fixture.createCustomer(tenant, "Theirs Ltd", someoneElse, null, null));
            theirContact.set(fixture.createContact(tenant, theirCustomer.get(), "theirs@example.com"));
            UUID role = roles.createRole("Assigned Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ASSIGNED));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.update(theirCustomer.get(), theirContact.get(),
                        new CustomerContactService.UpdateContactRequest(
                                "Hijacked", "hijacked@example.com", null, null, false, ContactStatus.ACTIVE))))
                .isInstanceOf(NoSuchElementException.class)
                .isNotInstanceOf(AccessDeniedException.class);
    }

    /**
     * The customerId in the PUT path is verified, not decorative.
     *
     * The controller binds it from a URL of the form
     * /customers/{customerId}/contacts/{contactId}, and nothing used to check that
     * the contact actually hung off that customer -- so any customer id at all
     * addressed the same contact. Not a scope bypass, because the contact-level
     * predicate binds regardless of what the path says, but a path variable that
     * means nothing invites a future caller to trust it, and the frontend already
     * keys its cache invalidation off exactly this segment.
     *
     * Both customers here belong to the SAME owner and the actor holds ALL scope,
     * so the contact is genuinely reachable and the mismatch is the only thing
     * that can reject the call. 404 rather than 403: a contact that is not under
     * the customer named is, as far as that URL is concerned, not there.
     */
    @Test
    void updatingAContactThroughAnotherCustomersPathIsNotFound() {
        UUID tenant = fixture.createTenant("contact-path-mismatch");
        var user = new AtomicReference<UUID>();
        var otherCustomer = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "mismatch@example.com"));
            UUID owning = fixture.createCustomer(tenant, "Owning Ltd", user.get(), null, null);
            otherCustomer.set(fixture.createCustomer(tenant, "Other Ltd", user.get(), null, null));
            contactId.set(fixture.createContact(tenant, owning, "held@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.update(otherCustomer.get(), contactId.get(),
                        new CustomerContactService.UpdateContactRequest(
                                "Moved", "moved@example.com", null, null, false, ContactStatus.ACTIVE))))
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
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "editor@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Retire Ltd", user.get(), null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), "typo@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () -> {
            var updated = contacts.update(customerId.get(), contactId.get(),
                    new CustomerContactService.UpdateContactRequest(
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
        var customerId = new AtomicReference<UUID>();
        var second = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "dupe-edit@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Dupe Edit Ltd", user.get(), null, null));
            fixture.createContact(tenant, customerId.get(), "first@example.com");
            second.set(fixture.createContact(tenant, customerId.get(), "second@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.update(customerId.get(), second.get(),
                        new CustomerContactService.UpdateContactRequest(
                                "Second Person", "first@example.com", null, null, false, ContactStatus.ACTIVE))))
                .isInstanceOf(DuplicateContactEmailException.class);
    }

    /**
     * A different constraint must NOT be reported as a duplicate address, or the
     * caller goes hunting for a duplicate that is not there.
     *
     * The customer is deliberately IN SCOPE and the violation is full_name's NOT
     * NULL (V8). An earlier version reached for a non-existent customer id to trip
     * the foreign key, and the parent scope check added a commit later silently
     * voided it: findOne returned empty and NoSuchElementException was thrown
     * before any SQL write, so the assertion below still passed while NO
     * DataIntegrityViolationException was raised at all. Widening violates() to
     * translate every integrity violation would not have turned it red — it had
     * become a test that could not fail.
     *
     * Hence the FIRST assertion, which is the anti-vacuity guard: the write must
     * actually reach the database and be refused there. "Not a duplicate" alone is
     * satisfied just as well by never getting that far.
     */
    @Test
    void anUnrelatedConstraintViolationIsNotReportedAsADuplicateEmail() {
        UUID tenant = fixture.createTenant("contact-not-null");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "notnull@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Not Null Ltd", user.get(), null, null));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, user.get(),
                () -> contacts.create(customerId.get(), new CustomerContactService.CreateContactRequest(
                        null, "no-name@example.com", null, null, false))))
                .as("the write must reach the database, or this guard proves nothing")
                .isInstanceOf(DataIntegrityViolationException.class)
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

    /**
     * Contacts were the only business entity in this sub-project whose writes went
     * unrecorded: customers audited create, update and deactivate from Task 20,
     * while the contact write surface arrived last (R2) and audited nothing.
     * Inviting a contact was always audited, via invitation.sent, which made the
     * gap easy to miss.
     */
    @Test
    void creatingAContactWritesATimelineVisibleAuditEvent() {
        UUID tenant = fixture.createTenant("contact-audit-create");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "auditcreate@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Audited Ltd", user.get(), null, null));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            contactId.set(contacts.create(customerId.get(),
                    new CustomerContactService.CreateContactRequest(
                            "Dana Adeyemi", "dana@example.com", "Ops Lead", null, true)).id()));

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("contact.created:" + contactId.get());

            // The flag, not just the event: timeline_visible is what sub-project 2's
            // Activity Timeline reads, and a contact appearing on a customer is
            // exactly the kind of change that timeline exists to show.
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> "contact.created".equals(e.getAction()))
                    .allMatch(e -> e.isTimelineVisible());
        });
    }

    @Test
    void updatingAContactWritesAnAuditEvent() {
        UUID tenant = fixture.createTenant("contact-audit-update");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "auditupdate@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Edited Ltd", user.get(), null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), "before@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            contacts.update(customerId.get(), contactId.get(),
                    new CustomerContactService.UpdateContactRequest(
                            "After Name", "after@example.com", null, null,
                            false, ContactStatus.ACTIVE)));

        fixture.runAs(tenant, () ->
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("contact.updated:" + contactId.get()));
    }

    /**
     * Retirement gets its OWN action rather than riding contact.updated.
     *
     * The shapes do not match the customer path and cannot be copied from it:
     * CustomerService.deactivate is a separate method behind its own
     * customer.deactivate permission, whereas a contact is retired through the
     * ordinary update PUT under contact.manage — there is no separate endpoint to
     * hang an action on. But the reason customer.deactivated exists applies
     * identically: business records are never deleted, so INACTIVE is the only
     * retirement a contact has and this event is the only record that it happened.
     * Logged as "Updated contact" it would be indistinguishable on the timeline
     * from a phone-number correction.
     *
     * Exactly one event per user action — the retirement records the deactivation
     * INSTEAD of an update, never both, or a single click would appear twice.
     */
    @Test
    void retiringAContactIsRecordedAsADeactivationNotAnUpdate() {
        UUID tenant = fixture.createTenant("contact-audit-retire");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "auditretire@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Retiring Ltd", user.get(), null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), "leaving@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        fixture.runAsUser(tenant, user.get(), () ->
            contacts.update(customerId.get(), contactId.get(),
                    new CustomerContactService.UpdateContactRequest(
                            "Leaving Person", "leaving@example.com", null, null,
                            false, ContactStatus.INACTIVE)));

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .extracting(e -> e.getAction() + ":" + e.getResourceId())
                    .contains("contact.deactivated:" + contactId.get())
                    .doesNotContain("contact.updated:" + contactId.get());
        });
    }

    /**
     * The anti-vacuity guard on the test above: it proves the service detects the
     * TRANSITION into INACTIVE rather than merely reading the resulting status.
     * Without it, an implementation that logged contact.deactivated whenever the
     * incoming status was INACTIVE would pass — and correcting a typo on an
     * already-retired contact would log a second retirement that never happened.
     */
    @Test
    void editingAnAlreadyRetiredContactIsAnUpdateNotAnotherDeactivation() {
        UUID tenant = fixture.createTenant("contact-audit-reedit");
        var user = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            user.set(fixture.createUser(tenant, "auditreedit@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Reedit Ltd", user.get(), null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), "gone@example.com"));
            UUID role = roles.createRole("Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ALL));
            roles.assignRole(user.get(), role);
        });

        // Retire first, then edit the retired record.
        fixture.runAsUser(tenant, user.get(), () -> {
            contacts.update(customerId.get(), contactId.get(),
                    new CustomerContactService.UpdateContactRequest(
                            "Gone Person", "gone@example.com", null, null,
                            false, ContactStatus.INACTIVE));
            contacts.update(customerId.get(), contactId.get(),
                    new CustomerContactService.UpdateContactRequest(
                            "Gone Person", "corrected@example.com", null, null,
                            false, ContactStatus.INACTIVE));
        });

        fixture.runAs(tenant, () -> {
            assertThat(auditEvents.findAll())
                    .filteredOn(e -> contactId.get().equals(e.getResourceId()))
                    .extracting(e -> e.getAction())
                    .as("one retirement, one ordinary edit — never two retirements")
                    .containsExactlyInAnyOrder("contact.deactivated", "contact.updated");
        });
    }
}
