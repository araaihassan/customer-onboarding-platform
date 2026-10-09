package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.customer.CustomerService;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WORKFLOW_PUBLISHED (6B spec 1.2.6) and NEW_CUSTOMER (plan amendment 8): a new workflow version
 * tells each case owner how many of their visible open cases are on an earlier version; making
 * someone a customer's owner tells them. Creating a customer notifies nobody (owner = actor).
 */
class WorkflowAndCustomerNotificationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired NotificationTestSupport support;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerService customers;

    private UUID user(UUID t, String email, Map<String, Scope> grants) {
        UUID u = fixture.runAsReturning(t, () -> fixture.createUser(t, email));
        if (!grants.isEmpty()) support.grant(t, u, grants);
        return u;
    }

    private void openCase(UUID t, UUID templateId, UUID owner, String ownerLabel) {
        fixture.runAs(t, () -> {
            UUID customer = fixture.createCustomerOwnedBy(t, "Cust " + ownerLabel + Uuid7.generate(), owner);
            cases.create(new CreateCaseRequest(customer, templateId, "Case " + ownerLabel, Map.of()));
        });
    }

    private void publishNextVersion(UUID t, UUID templateId) {
        fixture.runAs(t, () -> publishService.publish(workflows.createDraft(templateId)));
    }

    private List<Map<String, Object>> published(UUID t, UUID recipient) {
        return support.rowsFor(t, recipient).stream()
                .filter(r -> "WORKFLOW_PUBLISHED".equals(r.get("type"))).toList();
    }

    @Test
    void publishingANewVersionTellsOwnersOfOpenCasesOnEarlierVersions() {
        UUID t = fixture.createTenant("wf-published");
        UUID alice = user(t, "alice@wf-published.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID bob = user(t, "bob@wf-published.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ALL));
        UUID templateId = fixture.runAsReturning(t, () -> journey.publishedTemplate());
        for (int i = 0; i < 3; i++) openCase(t, templateId, alice, "a" + i);
        openCase(t, templateId, bob, "b0");
        openCase(t, templateId, bob, "b1");
        ownerJdbc().update("update onboarding_case set status = 'COMPLETED' where owner_user_id = ? and name = 'Case b1'", bob);

        publishNextVersion(t, templateId);

        var toAlice = published(t, alice);
        assertThat(toAlice).hasSize(1);
        assertThat(toAlice.get(0).get("body")).isEqualTo("3 of your open cases are on an earlier version.");
        assertThat((String) toAlice.get(0).get("title")).endsWith(" v2 is live");
        assertThat(toAlice.get(0).get("subject_type")).isEqualTo("workflow_version");
        assertThat(toAlice.get(0).get("case_id")).isNull();
        var toBob = published(t, bob);
        assertThat(toBob).hasSize(1);
        assertThat(toBob.get(0).get("body")).isEqualTo("1 of your open cases is on an earlier version.");
    }

    @Test
    void anOwnerWhoCannotSeeTheirCasesIsNotTold() {
        UUID t = fixture.createTenant("wf-noview");
        UUID carol = user(t, "carol@wf-noview.test", Map.of());
        UUID templateId = fixture.runAsReturning(t, () -> journey.publishedTemplate());
        openCase(t, templateId, carol, "c0");

        publishNextVersion(t, templateId);

        assertThat(published(t, carol)).isEmpty();
    }

    @Test
    void anOwnerHoldingCaseViewOnlyAtAssignedScopeIsTold() {
        UUID t = fixture.createTenant("wf-assigned");
        UUID erin = user(t, "erin@wf-assigned.test", Map.of(PermissionKeys.CASE_VIEW, Scope.ASSIGNED));
        UUID templateId = fixture.runAsReturning(t, () -> journey.publishedTemplate());
        openCase(t, templateId, erin, "e0");

        publishNextVersion(t, templateId);

        assertThat(published(t, erin)).hasSize(1);
    }

    @Test
    void assigningACustomerToSomeoneElseNotifiesThem() {
        UUID t = fixture.createTenant("cust-assigned");
        UUID dave = user(t, "dave@cust-assigned.test", Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.ALL));
        UUID customerId = fixture.runAsReturning(t, () -> fixture.createCustomer(t, "Acme", null, null, null));
        UUID admin = fixture.createAdministrator(t, "admin+" + Uuid7.generate() + "@example.com");

        // A real field changes alongside the owner, so the new values are proven written.
        fixture.runAsUser(t, admin, () -> customers.update(customerId, new CustomerService.UpdateCustomerRequest(
                "Acme Holdings Ltd", "Acme Holdings", null, null, null, dave, null, null)));

        var rows = support.rowsFor(t, dave).stream().filter(r -> "NEW_CUSTOMER".equals(r.get("type"))).toList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("title")).isEqualTo("Customer assigned to you: Acme Holdings");
        assertThat(rows.get(0).get("link_path")).isEqualTo("/t/cust-assigned/customers/" + customerId);
        assertThat(rows.get(0).get("subject_type")).isEqualTo("customer");
    }

    @Test
    void aTeamScopedNewOwnerIsNotifiedOnlyForACustomerInScope() {
        UUID t = fixture.createTenant("cust-team");
        UUID frank = user(t, "frank@cust-team.test", Map.of(PermissionKeys.CUSTOMER_VIEW, Scope.TEAM));
        UUID customerId = fixture.runAsReturning(t, () -> fixture.createCustomer(t, "Out of team", null, null, null));
        UUID admin = fixture.createAdministrator(t, "admin+" + Uuid7.generate() + "@example.com");

        fixture.runAsUser(t, admin, () -> customers.update(customerId, new CustomerService.UpdateCustomerRequest(
                "Out of team Ltd", "Out of team", null, null, null, frank, null, null)));

        assertThat(support.rowsFor(t, frank).stream().filter(r -> "NEW_CUSTOMER".equals(r.get("type")))).isEmpty();
    }

    @Test
    void creatingACustomerNotifiesNobody() {
        UUID t = fixture.createTenant("cust-created");
        UUID admin = fixture.createAdministrator(t, "admin+" + Uuid7.generate() + "@example.com");

        fixture.runAsUser(t, admin, () -> customers.create(new CustomerService.CreateCustomerRequest(
                "Zed Ltd", "Zed", null, null, null, null, null)));

        assertThat(support.notifications(t)).isEmpty();
    }
}
