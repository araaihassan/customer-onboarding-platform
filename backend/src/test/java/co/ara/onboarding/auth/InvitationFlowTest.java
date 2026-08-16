package co.ara.onboarding.auth;

import co.ara.onboarding.customer.CustomerContactRepository;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.identity.UserStatus;
import co.ara.onboarding.platform.UserType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exception assertions wrap runAs rather than sitting inside it — the helper runs
 * in a TransactionTemplate, and catching inside leaves it rollback-only. See Global
 * Constraints.
 */
class InvitationFlowTest extends PostgresTestBase {

    @Autowired InvitationService invitations;
    @Autowired ActivationService activations;
    @Autowired CustomerContactRepository contacts;
    @Autowired AppUserRepository users;
    @Autowired TenantFixture fixture;

    @Test
    void acceptingAnInvitationCreatesAPortalUserAndLinksTheContact() {
        UUID tenant = fixture.createTenant("invite-co");

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Invitee Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "guest@invitee.example");

            String token = invitations.issue(contactId);
            AppUser created = activations.accept(token, "a-sufficiently-long-password");

            assertThat(created.getUserType()).isEqualTo(UserType.PORTAL);
            assertThat(created.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(contacts.findById(contactId).orElseThrow().getUserId())
                    .isEqualTo(created.getId());
        });
    }

    @Test
    void anInvitationTokenCannotBeUsedTwice() {
        UUID tenant = fixture.createTenant("single-use");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Once Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "once@example.com");
            token.set(invitations.issue(contactId));
            activations.accept(token.get(), "a-sufficiently-long-password");
        });

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> activations.accept(token.get(), "another-password")))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void expiredInvitationIsRejected() {
        UUID tenant = fixture.createTenant("expired-invite");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Late Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "late@example.com");
            token.set(invitations.issue(contactId));
            fixture.expireInvitations();
        });

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> activations.accept(token.get(), "a-sufficiently-long-password")))
                .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * The portal user must be PORTAL, never INTERNAL. A contact who activated with
     * userType INTERNAL would be eligible for internal roles and would see the staff
     * application rather than the portal.
     */
    @Test
    void createdPortalUserCannotHoldInternalRoles() {
        UUID tenant = fixture.createTenant("portal-type");

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Portal Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "portal@example.com");
            AppUser created = activations.accept(
                    invitations.issue(contactId), "long-enough-password");

            assertThat(created.getUserType()).isEqualTo(UserType.PORTAL);
            assertThat(users.findById(created.getId()).orElseThrow().getUserType())
                    .as("and it must be PORTAL as persisted, not only on the returned object")
                    .isEqualTo(UserType.PORTAL);
        });
    }

    /**
     * Not in the plan, and the reason issuing and accepting are separate services.
     * invitation.send is a real catalogued permission, so issuing must be gated;
     * accepting is performed by an unauthenticated person holding a token and cannot
     * be. A single service carrying both could not satisfy AuthorizationCoverageTest
     * without exempting the gated half too.
     */
    @Test
    void issuingAnInvitationRequiresThePermission() {
        UUID tenant = fixture.createTenant("invite-gated");
        var contactId = new AtomicReference<UUID>();
        var ungranted = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Gated Ltd", null, null, null);
            contactId.set(fixture.createContact(tenant, customerId, "gated@example.com"));
            ungranted.set(fixture.createUser(tenant, "nobody@example.com"));
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, ungranted.get(),
                () -> invitations.issue(contactId.get())))
                .isInstanceOf(AccessDeniedException.class);
    }

    /**
     * Not in the plan. Activation creates an app_user, and app_user is unique on
     * (tenant, lower(email)) — so a contact sharing an address with an existing user
     * would otherwise fail on the constraint and surface as a 500. Rejected as an
     * invalid token instead, which is also the right answer: the invitation cannot
     * create the account it promises.
     */
    @Test
    void invitationForAnAddressThatAlreadyHasAnAccountIsRejected() {
        UUID tenant = fixture.createTenant("invite-collide");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID customerId = fixture.createCustomer(tenant, "Collide Ltd", null, null, null);
            UUID contactId = fixture.createContact(tenant, customerId, "taken@example.com");
            token.set(invitations.issue(contactId));
            fixture.createUser(tenant, "taken@example.com");
        });

        assertThatThrownBy(() -> fixture.runAs(tenant,
                () -> activations.accept(token.get(), "a-sufficiently-long-password")))
                .isInstanceOf(InvalidTokenException.class);
    }
}
