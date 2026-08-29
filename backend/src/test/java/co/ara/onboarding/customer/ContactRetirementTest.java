package co.ara.onboarding.customer;

import co.ara.onboarding.auth.ActivationService;
import co.ara.onboarding.auth.Invitation;
import co.ara.onboarding.auth.InvalidTokenException;
import co.ara.onboarding.auth.InvitationPurpose;
import co.ara.onboarding.auth.InvitationRepository;
import co.ara.onboarding.auth.LoginOutcome;
import co.ara.onboarding.auth.LoginService;
import co.ara.onboarding.auth.SecureTokens;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Three defects sharing one fixture: a retired contact could still sign in and
 * could still activate a pending invitation; a corrected email left the portal
 * login on the old address; and case-only-different contacts were accepted
 * while app_user's lower(email) index refused the second activation.
 */
class ContactRetirementTest extends PostgresTestBase {

    @Autowired CustomerContactService contacts;
    @Autowired ActivationService activation;
    @Autowired LoginService login;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired AppUserRepository users;
    @Autowired InvitationRepository invitations;
    @Autowired ContactInvitationSender invitationSender;

    private static CustomerContactService.UpdateContactRequest retireRequest(String email) {
        return new CustomerContactService.UpdateContactRequest(
                "Retired Person", email, null, null, false, ContactStatus.INACTIVE);
    }

    private static CustomerContactService.UpdateContactRequest emailChangedTo(String newEmail) {
        return new CustomerContactService.UpdateContactRequest(
                "Corrected Person", newEmail, null, null, false, ContactStatus.ACTIVE);
    }

    private static CustomerContactService.UpdateContactRequest reactivateRequest(String email) {
        return new CustomerContactService.UpdateContactRequest(
                "Reactivated Person", email, null, null, false, ContactStatus.ACTIVE);
    }

    @Test
    void aRetiredContactCannotSignIn() {
        UUID tenant = fixture.createTenant("contact-retire-login");
        String email = "retiree@example.com";
        String password = "correct-password-value";
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            customerId.set(fixture.createCustomer(tenant, "Retiring Login Ltd", null, null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), email));
        });
        fixture.runAs(tenant, () -> rawToken.set(fixture.issueInvitation(contactId.get())));
        fixture.runAs(tenant, () -> activation.accept(rawToken.get(), password));

        fixture.runAs(tenant, () -> contacts.update(customerId.get(), contactId.get(), retireRequest(email)));

        var outcome = new AtomicReference<LoginOutcome>();
        fixture.runAs(tenant, () -> outcome.set(login.login(email, password)));

        assertThat(outcome.get()).isInstanceOf(LoginOutcome.InvalidCredentials.class);
    }

    /**
     * Isolates ActivationService's own ContactStatus check from this task's OTHER
     * fix — retirement revoking any invitation outstanding at the time. If the
     * contact were retired WHILE holding an outstanding invitation, that fix would
     * revoke it, and isRedeemable's revokedAt != null check (reached inside
     * accept(), before activateContact() is ever called) would throw
     * InvalidTokenException for that unrelated reason — a test written that way
     * would pass even with the new ContactStatus check deleted entirely, exactly
     * the vacuous shape a review caught in this same plan's Task 4.
     *
     * So here the contact is retired FIRST, with no invitation outstanding yet,
     * and the invitation is built directly against the repository afterward —
     * never issued through InvitationService.issue — so nothing in this test ever
     * calls revokePendingInvitations for it. The sanity assertion below proves
     * that: this invitation is genuinely still redeemable by every OTHER rule, so
     * the InvalidTokenException from accept() can only be the status check.
     */
    @Test
    void aRetiredContactCannotActivateAPendingInvitation() {
        UUID tenant = fixture.createTenant("contact-retire-activate");
        String email = "already-retired@example.com";
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            customerId.set(fixture.createCustomer(tenant, "Already Retired Ltd", null, null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), email));
        });

        fixture.runAs(tenant, () -> contacts.update(customerId.get(), contactId.get(), retireRequest(email)));

        String rawToken = SecureTokens.generate();
        fixture.runAs(tenant, () -> {
            Invitation invitation = new Invitation();
            invitation.setId(Uuid7.generate());
            invitation.setTenantId(tenant);
            invitation.setPurpose(InvitationPurpose.ACTIVATION);
            invitation.setCustomerContactId(contactId.get());
            invitation.setTokenHash(SecureTokens.hash(rawToken));
            invitation.setExpiresAt(Instant.now().plus(Duration.ofDays(7)));
            invitations.saveAndFlush(invitation);
        });

        fixture.runAs(tenant, () ->
            assertThat(invitations.findByTokenHash(SecureTokens.hash(rawToken)).orElseThrow().getRevokedAt())
                    .as("this invitation was created after retirement ran, so nothing revoked it -- "
                            + "the rejection below can only be the new ContactStatus check")
                    .isNull());

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> activation.accept(rawToken, "new-password-value")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void retiringAContactRevokesAnOutstandingInvitation() {
        UUID tenant = fixture.createTenant("contact-retire-revoke");
        String email = "outstanding@example.com";
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            customerId.set(fixture.createCustomer(tenant, "Outstanding Ltd", null, null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), email));
        });
        fixture.runAs(tenant, () -> rawToken.set(fixture.issueInvitation(contactId.get())));

        fixture.runAs(tenant, () -> contacts.update(customerId.get(), contactId.get(), retireRequest(email)));

        fixture.runAs(tenant, () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get())).orElseThrow();
            assertThat(invitation.getRevokedAt()).isNotNull();
        });
    }

    @Test
    void correctingAContactEmailMovesThePortalLoginWithIt() {
        UUID tenant = fixture.createTenant("contact-email-sync");
        String oldEmail = "old@example.com";
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();
        var contactUserId = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            customerId.set(fixture.createCustomer(tenant, "Email Sync Ltd", null, null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), oldEmail));
        });
        fixture.runAs(tenant, () -> rawToken.set(fixture.issueInvitation(contactId.get())));
        fixture.runAs(tenant, () ->
                contactUserId.set(activation.accept(rawToken.get(), "some-password-value").getId()));

        fixture.runAs(tenant, () ->
            contacts.update(customerId.get(), contactId.get(), emailChangedTo("new@acme.test")));

        fixture.runAs(tenant, () ->
            assertThat(users.findById(contactUserId.get()).orElseThrow().getEmail())
                    .isEqualTo("new@acme.test"));
    }

    @Test
    void twoContactsDifferingOnlyInCaseAreRefused() {
        UUID tenant = fixture.createTenant("contact-case-collision");
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () ->
                customerId.set(fixture.createCustomer(tenant, "Case Collision Ltd", null, null, null)));

        fixture.runAs(tenant, () -> contacts.create(customerId.get(),
                new CustomerContactService.CreateContactRequest(
                        "Person One", "Person@acme.test", null, null, false)));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> contacts.create(customerId.get(),
                new CustomerContactService.CreateContactRequest(
                        "Person Two", "person@acme.test", null, null, false))))
                .isInstanceOf(DuplicateContactEmailException.class);
    }

    /**
     * The escalation shape CLAUDE.md names as having recurred multiple times
     * already (contact creation, role assignment, invitation issuance): a foreign
     * id passed straight through a port method must be re-resolved through
     * AuthorizedQuery in the implementation, not trusted just because today's one
     * caller (CustomerContactService.update) already resolved the same contact
     * itself. ContactInvitationSender is a public customer-module interface,
     * injectable by anything -- this proves revokePendingInvitations defends
     * itself even called directly, bypassing that caller entirely.
     *
     * The outstanding invitation is issued through the privileged fixture
     * administrator, not the narrow actor -- the narrow actor could not reach the
     * outsider's contact to invite them either, so this isolates the revoke
     * call's own scope check from issueInvitation's.
     */
    @Test
    void aNarrowlyScopedActorCannotRevokeInvitationsForAContactOutsideTheirScope() {
        UUID tenant = fixture.createTenant("contact-revoke-narrow");
        var narrowActor = new AtomicReference<UUID>();
        var theirContact = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID someoneElse = fixture.createUser(tenant, "owner@example.com");
            UUID theirCustomer = fixture.createCustomer(tenant, "Theirs Ltd", someoneElse, null, null);
            theirContact.set(fixture.createContact(tenant, theirCustomer, "theirs@example.com"));
            narrowActor.set(fixture.createUser(tenant, "narrow@example.com"));
            UUID role = roles.createRole("Assigned Contact Manager", "", Map.of(
                    PermissionKeys.CONTACT_MANAGE, Scope.ASSIGNED));
            roles.assignRole(narrowActor.get(), role);
        });

        fixture.runAs(tenant, () -> rawToken.set(fixture.issueInvitation(theirContact.get())));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor.get(),
                () -> invitationSender.revokePendingInvitations(theirContact.get())))
                .as("a narrowly scoped actor must not reach a contact outside their scope")
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAs(tenant, () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get())).orElseThrow();
            assertThat(invitation.getRevokedAt())
                    .as("the refusal must not have revoked anything")
                    .isNull();
        });
    }

    /**
     * Fix round 1's own finding: before this, retiring a contact was a one-way
     * door for its linked portal account -- UserAdminService has no reactivate
     * path, and both of ActivationService's own setStatus(ACTIVE) sites are
     * unreachable for an already-activated contact (activateInternalUser
     * requires INVITED; activateContact refuses because an app_user already
     * exists for the address). A mis-clicked retirement was recoverable only by
     * direct SQL. This proves the symmetric transition: editing a retired
     * contact back to ACTIVE restores its linked account's own ACTIVE status,
     * and the ORIGINAL password (never touched by deactivate) still logs in.
     */
    @Test
    void reactivatingARetiredContactRestoresPortalAccess() {
        UUID tenant = fixture.createTenant("contact-reactivate");
        String email = "comeback@example.com";
        String password = "correct-password-value";
        var customerId = new AtomicReference<UUID>();
        var contactId = new AtomicReference<UUID>();
        var contactUserId = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            customerId.set(fixture.createCustomer(tenant, "Comeback Ltd", null, null, null));
            contactId.set(fixture.createContact(tenant, customerId.get(), email));
        });
        fixture.runAs(tenant, () -> rawToken.set(fixture.issueInvitation(contactId.get())));
        fixture.runAs(tenant, () ->
                contactUserId.set(activation.accept(rawToken.get(), password).getId()));

        // Retire, then undo -- a plain mis-click-and-fix cycle through the same
        // PUT the product actually offers, nothing more exotic.
        fixture.runAs(tenant, () -> contacts.update(customerId.get(), contactId.get(), retireRequest(email)));
        fixture.runAs(tenant, () ->
                contacts.update(customerId.get(), contactId.get(), reactivateRequest(email)));

        fixture.runAs(tenant, () ->
            assertThat(users.findById(contactUserId.get()).orElseThrow().getStatus())
                    .isEqualTo(UserStatus.ACTIVE));

        var outcome = new AtomicReference<LoginOutcome>();
        fixture.runAs(tenant, () -> outcome.set(login.login(email, password)));
        assertThat(outcome.get())
                .as("the original credential still works -- reactivate never touched the password hash")
                .isInstanceOf(LoginOutcome.Success.class);
    }

    /**
     * Fix round 1's other finding. app_user is unique on (tenant_id,
     * lower(email)) TENANT-WIDE, while customer_contact's own uniqueness (V14)
     * is only PER-CUSTOMER -- so correcting one customer's contact onto an
     * address a DIFFERENT customer's already-activated contact already holds
     * passes CustomerContactService's own duplicate check (different
     * customer_id) and only then collides with app_user's index. Before this
     * fix LinkedPortalUserEmailSync.syncEmail called users.save with no flush
     * and no translation, so this surfaced as a raw DataIntegrityViolationException
     * at commit, outside the transaction proxy -- exactly the failure shape
     * CustomerContactService.save's own javadoc explains saveAndFlush exists to
     * prevent for the sibling constraint. Both contacts must already be
     * activated for this to reach the collision at all: an unlinked contact's
     * syncEmail call is a no-op (userId null), so nothing would ever touch
     * app_user and nothing would ever collide.
     */
    @Test
    void correctingAContactEmailOntoAnotherCustomersLinkedAccountIsAConflict() {
        UUID tenant = fixture.createTenant("contact-email-cross-conflict");
        String takenEmail = "taken@example.com";
        String correctingEmail = "correcting@example.com";
        var customerA = new AtomicReference<UUID>();
        var customerB = new AtomicReference<UUID>();
        var contactA = new AtomicReference<UUID>();
        var contactB = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            customerA.set(fixture.createCustomer(tenant, "Customer A Ltd", null, null, null));
            customerB.set(fixture.createCustomer(tenant, "Customer B Ltd", null, null, null));
            contactA.set(fixture.createContact(tenant, customerA.get(), takenEmail));
            contactB.set(fixture.createContact(tenant, customerB.get(), correctingEmail));
        });

        var tokenA = new AtomicReference<String>();
        var tokenB = new AtomicReference<String>();
        fixture.runAs(tenant, () -> tokenA.set(fixture.issueInvitation(contactA.get())));
        fixture.runAs(tenant, () -> tokenB.set(fixture.issueInvitation(contactB.get())));
        fixture.runAs(tenant, () -> activation.accept(tokenA.get(), "password-a-value"));
        fixture.runAs(tenant, () -> activation.accept(tokenB.get(), "password-b-value"));

        assertThatThrownBy(() -> fixture.runAs(tenant, () -> contacts.update(customerB.get(), contactB.get(),
                new CustomerContactService.UpdateContactRequest(
                        "Contact B", takenEmail, null, null, false, ContactStatus.ACTIVE))))
                .as("an actionable conflict, not a raw DataIntegrityViolationException")
                .isInstanceOf(PortalEmailConflictException.class);
    }
}
