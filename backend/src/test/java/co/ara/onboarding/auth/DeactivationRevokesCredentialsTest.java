package co.ara.onboarding.auth;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.identity.UserActivationSender;
import co.ara.onboarding.identity.UserAdminService;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deactivation must end pending credentials, not just sessions (CLAUDE.md's own
 * open item). PasswordResetService consulted status nowhere, so a DEACTIVATED
 * account could both complete a reset issued before deactivation and request a
 * brand new one afterward -- and every outstanding invitation (activation or
 * password-reset, one table distinguished only by InvitationPurpose) stayed
 * redeemable. Two independent bugs, not one bug with two symptoms.
 */
class DeactivationRevokesCredentialsTest extends PostgresTestBase {

    @Autowired PasswordResetService resets;
    @Autowired UserAdminService admin;
    @Autowired RoleService roles;
    @Autowired UserActivationSender activationSender;
    @Autowired InvitationRepository invitations;
    @Autowired TenantFixture fixture;

    /** A tenant, an actor holding USER_MANAGE at ALL, and a plain target user. */
    private record World(UUID tenant, UUID actor, UUID targetId, String email) {}

    private World world(String slug, String email) {
        UUID tenant = fixture.createTenant(slug);
        var actor = new AtomicReference<UUID>();
        var target = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            actor.set(fixture.createUser(tenant, "boss+" + slug + "@example.com"));
            target.set(fixture.createUser(tenant, email));
            UUID role = roles.createRole("User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.ALL,
                    PermissionKeys.USER_MANAGE, Scope.ALL));
            roles.assignRole(actor.get(), role);
        });

        return new World(tenant, actor.get(), target.get(), email);
    }

    @Test
    void aDeactivatedUserCannotCompleteAPasswordReset() {
        World w = world("deact-reset-complete", "reset-complete@example.com");
        var token = new AtomicReference<String>();

        // Token issued while ACTIVE, redeemed after deactivation.
        fixture.runAs(w.tenant(), () -> token.set(resets.request(w.email()).orElseThrow()));
        fixture.runAsUser(w.tenant(), w.actor(), () -> admin.deactivate(w.targetId()));

        assertThatThrownBy(() -> fixture.runAs(w.tenant(),
                () -> resets.reset(token.get(), "new-password-value")))
                .isInstanceOf(InvalidTokenException.class);
    }

    /**
     * The half CLAUDE.md's own wording names separately from "complete": a
     * deactivated user must not be able to obtain a NEW token either, and the
     * response must not distinguish "deactivated" from "no such address" -- both
     * already collapse to Optional.empty() for the unknown-address case, and this
     * must land in the same bucket.
     */
    @Test
    void aDeactivatedUserCannotRequestANewPasswordReset() {
        World w = world("deact-reset-request", "reset-request@example.com");

        fixture.runAsUser(w.tenant(), w.actor(), () -> admin.deactivate(w.targetId()));

        fixture.runAs(w.tenant(), () -> assertThat(resets.request(w.email())).isEmpty());
    }

    @Test
    void deactivationRevokesAnOutstandingActivationInvitation() {
        World w = world("deact-invite", "invite-target@example.com");
        var rawToken = new AtomicReference<String>();

        fixture.runAsUser(w.tenant(), w.actor(),
                () -> rawToken.set(activationSender.issueForUser(w.targetId())));
        fixture.runAsUser(w.tenant(), w.actor(), () -> admin.deactivate(w.targetId()));

        fixture.runAs(w.tenant(), () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get()))
                    .orElseThrow();
            assertThat(invitation.getRevokedAt()).isNotNull();
        });
    }

    @Test
    void deactivationRevokesAnOutstandingPasswordResetToken() {
        World w = world("deact-reset-token", "reset-token@example.com");
        var rawToken = new AtomicReference<String>();

        fixture.runAs(w.tenant(), () -> rawToken.set(resets.request(w.email()).orElseThrow()));
        fixture.runAsUser(w.tenant(), w.actor(), () -> admin.deactivate(w.targetId()));

        fixture.runAs(w.tenant(), () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get()))
                    .orElseThrow();
            assertThat(invitation.getRevokedAt()).isNotNull();
        });
    }

    /**
     * Positive control: an ACTIVE user's own reset still works end-to-end. Without
     * this, a defect that refused every reset (not just a deactivated one's) would
     * pass every test above.
     */
    @Test
    void anActiveUsersPasswordResetStillWorks() {
        UUID tenant = fixture.createTenant("deact-reset-control");
        fixture.createUserWithPassword(tenant, "active-control@example.com", "old-password-value");
        var token = new AtomicReference<String>();

        fixture.runAs(tenant, () ->
                token.set(resets.request("active-control@example.com").orElseThrow()));

        fixture.runAs(tenant, () -> resets.reset(token.get(), "brand-new-password-value"));
    }

    /**
     * Fix round 1's own finding. issueForUser resolves its userId through
     * AuthorizedQuery before doing anything -- revokePendingInvitations, right next
     * to it on the same interface with the same permission, did not, and was safe
     * only because its one caller (UserAdminService.deactivate) happened to
     * already resolve the id itself. UserActivationSender is a public
     * identity-module interface, injectable by anything: this proves the method
     * now defends itself even when called directly, bypassing that caller
     * entirely, exactly the shape CLAUDE.md names as having recurred multiple
     * times already (contact creation, role assignment, invitation issuance).
     *
     * The outstanding invitation is issued through the privileged fixture
     * administrator, not the narrow actor -- the narrow actor could not reach the
     * outsider to invite them either, so this isolates the revoke call's own
     * scope check from issueForUser's.
     */
    @Test
    void departmentScopedActorCannotRevokeInvitationsForAUserOutsideTheirDepartment() {
        UUID tenant = fixture.createTenant("deact-invite-narrow");
        var narrowActor = new AtomicReference<UUID>();
        var outsider = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "Dept A");
            UUID deptB = fixture.createDepartment(tenant, "Dept B");
            narrowActor.set(fixture.createUserInDepartment(tenant, "narrow@example.com", deptA));
            outsider.set(fixture.createUserInDepartment(tenant, "outsider@example.com", deptB));

            UUID role = roles.createRole("Narrow User Admin", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.DEPARTMENT,
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            roles.assignRole(narrowActor.get(), role);
        });

        fixture.runAs(tenant, () ->
                rawToken.set(activationSender.issueForUser(outsider.get())));

        assertThatThrownBy(() -> fixture.runAsUser(tenant, narrowActor.get(),
                () -> activationSender.revokePendingInvitations(outsider.get())))
                .as("a department-scoped actor must not reach a user outside their department")
                .isInstanceOf(NoSuchElementException.class);

        fixture.runAs(tenant, () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get()))
                    .orElseThrow();
            assertThat(invitation.getRevokedAt())
                    .as("the refusal must not have revoked anything")
                    .isNull();
        });
    }

    /** Positive control: revoking within one's own department still works. */
    @Test
    void departmentScopedActorCanRevokeInvitationsWithinTheirDepartment() {
        UUID tenant = fixture.createTenant("deact-invite-narrow-in");
        var narrowActor = new AtomicReference<UUID>();
        var peer = new AtomicReference<UUID>();
        var rawToken = new AtomicReference<String>();

        fixture.runAs(tenant, () -> {
            UUID deptA = fixture.createDepartment(tenant, "Dept A");
            narrowActor.set(fixture.createUserInDepartment(tenant, "narrowin@example.com", deptA));
            peer.set(fixture.createUserInDepartment(tenant, "peerin@example.com", deptA));

            UUID role = roles.createRole("Narrow User Admin In", "", Map.of(
                    PermissionKeys.USER_VIEW, Scope.DEPARTMENT,
                    PermissionKeys.USER_MANAGE, Scope.DEPARTMENT));
            roles.assignRole(narrowActor.get(), role);
        });

        fixture.runAsUser(tenant, narrowActor.get(),
                () -> rawToken.set(activationSender.issueForUser(peer.get())));

        fixture.runAsUser(tenant, narrowActor.get(),
                () -> activationSender.revokePendingInvitations(peer.get()));

        fixture.runAs(tenant, () -> {
            Invitation invitation = invitations.findByTokenHash(SecureTokens.hash(rawToken.get()))
                    .orElseThrow();
            assertThat(invitation.getRevokedAt()).isNotNull();
        });
    }
}
