package co.ara.onboarding.scoping;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.notification.Notification;
import co.ara.onboarding.notification.NotificationRepository;
import co.ara.onboarding.notification.NotificationType;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec 7.2: a notification is read by its recipient only, at every scope below ALL. */
class NotificationDescriptorTest extends PostgresTestBase {

    @Autowired AuthorizedQuery authorizedQuery;
    @Autowired RoleService roles;
    @Autowired TenantFixture fixture;
    @Autowired NotificationRepository notifications;

    private UUID notify(UUID tenant, UUID recipient) {
        Notification n = new Notification();
        n.setId(Uuid7.generate());
        n.setTenantId(tenant);
        n.setRecipientUserId(recipient);
        n.setType(NotificationType.ESCALATION);
        n.setTitle("t");
        n.setBody("b");
        n.setLinkPath("/x");
        return notifications.saveAndFlush(n).getId();
    }

    private List<UUID> visible(UUID tenant, UUID user) {
        var out = new AtomicReference<List<UUID>>();
        fixture.runAsUser(tenant, user, () -> out.set(authorizedQuery
                .findAll(notifications, Notification.class, PermissionKeys.SLA_VIEW, null, Pageable.unpaged())
                .map(Notification::getId).getContent()));
        return out.get();
    }

    private void onlyMineAt(Scope scope) {
        UUID tenant = fixture.createTenant("notif-" + scope.name().toLowerCase());
        UUID[] u = new UUID[2];
        UUID[] mine = new UUID[1];
        fixture.runAs(tenant, () -> {
            UUID dept = fixture.createDepartment(tenant, "D");
            UUID team = fixture.createTeam(tenant, "T");
            u[0] = fixture.createUserInDepartment(tenant, "a@" + scope + ".example", dept);
            u[1] = fixture.createUserInDepartment(tenant, "b@" + scope + ".example", dept);
            fixture.addToTeam(tenant, u[0], team);
            fixture.addToTeam(tenant, u[1], team);
            roles.assignRole(u[0], roles.createRole("Notif " + scope, "", Map.of(PermissionKeys.SLA_VIEW, scope)));
            mine[0] = notify(tenant, u[0]);
            notify(tenant, u[1]);
        });
        assertThat(visible(tenant, u[0])).containsExactly(mine[0]);
    }

    @Test void teamScopeSeesOnlyOwnNotifications() { onlyMineAt(Scope.TEAM); }
    @Test void departmentScopeSeesOnlyOwnNotifications() { onlyMineAt(Scope.DEPARTMENT); }
    @Test void assignedScopeSeesOnlyOwnNotifications() { onlyMineAt(Scope.ASSIGNED); }

    @Test
    void allSeesEverythingAndNoGrantSeesNothing() {
        UUID tenant = fixture.createTenant("notif-all");
        UUID[] u = new UUID[3];
        fixture.runAs(tenant, () -> {
            u[0] = fixture.createUser(tenant, "a@notif-all.example");
            u[1] = fixture.createUser(tenant, "b@notif-all.example");
            u[2] = fixture.createUser(tenant, "c@notif-all.example");
            roles.assignRole(u[0], roles.createRole("Notif All", "", Map.of(PermissionKeys.SLA_VIEW, Scope.ALL)));
            notify(tenant, u[1]);
            notify(tenant, u[2]);
        });
        assertThat(visible(tenant, u[0])).hasSize(2);
        assertThat(visible(tenant, u[1])).isEmpty();
    }
}
