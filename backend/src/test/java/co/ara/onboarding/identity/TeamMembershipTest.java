package co.ara.onboarding.identity;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.CustomerService;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TeamMembershipTest extends PostgresTestBase {

    @Autowired OrgStructureService org;
    @Autowired CustomerService customers;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;

    /**
     * The assertion that matters is not "a row was written" but "TEAM scope now
     * resolves". Sub-project 1 had the row-writing path in a test fixture and still
     * shipped four role templates that granted nothing.
     */
    @Test
    void aMemberAddedThroughTheApiCanSeeTheTeamsCustomers() {
        UUID tenant = fixture.createTenant("team-live");
        var teamId = new AtomicReference<UUID>();
        var userId = new AtomicReference<UUID>();
        var customerId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            userId.set(fixture.createUser(tenant, "member@example.com"));
            customerId.set(fixture.createCustomer(tenant, "Acme", null, null, teamId.get()));
            UUID role = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(userId.get(), role);
        });

        // Before membership: TEAM scope resolves to nothing.
        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))).isEmpty());

        fixture.runAs(tenant, () -> org.addTeamMember(teamId.get(), userId.get()));

        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))
                        .map(CustomerService.CustomerView::id))
                        .containsExactly(customerId.get()));
    }

    @Test
    void removingAMemberRevokesTeamScopeOnTheNextRequest() {
        UUID tenant = fixture.createTenant("team-revoke");
        var teamId = new AtomicReference<UUID>();
        var userId = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            userId.set(fixture.createUser(tenant, "leaver@example.com"));
            fixture.createCustomer(tenant, "Acme", null, null, teamId.get());
            UUID role = roles.createRole("Team Viewer", "",
                    Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
            roles.assignRole(userId.get(), role);
            org.addTeamMember(teamId.get(), userId.get());
        });

        fixture.runAs(tenant, () -> org.removeTeamMember(teamId.get(), userId.get()));

        fixture.runAsUser(tenant, userId.get(), () ->
                assertThat(customers.list(null, null, Pageable.ofSize(10))).isEmpty());
    }

    @Test
    void addingAMemberRequiresTeamManage() {
        UUID tenant = fixture.createTenant("team-gate");
        var teamId = new AtomicReference<UUID>();
        var weakUser = new AtomicReference<UUID>();

        fixture.runAs(tenant, () -> {
            teamId.set(fixture.createTeam(tenant, "Delivery"));
            weakUser.set(fixture.createUser(tenant, "weak@example.com"));
            UUID role = roles.createRole("User Viewer", "",
                    Map.of(PermissionKeys.USER_VIEW, Scope.ALL));
            roles.assignRole(weakUser.get(), role);
        });

        assertThatThrownBy(() -> fixture.runAsUser(tenant, weakUser.get(),
                () -> org.addTeamMember(teamId.get(), weakUser.get())))
                .isInstanceOf(AccessDeniedException.class);
    }
}
