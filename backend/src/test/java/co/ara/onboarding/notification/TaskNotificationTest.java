package co.ara.onboarding.notification;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.sla.SlaTestSupport;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.CreateTaskRequest;
import co.ara.onboarding.task.TaskPriority;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.TaskStatus;
import co.ara.onboarding.task.TaskStatusRequest;
import co.ara.onboarding.task.UpdateTaskRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static co.ara.onboarding.workflow.WorkflowFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

/** TASK_ASSIGNED (6B spec 5.2; plan amendment 1): create, reassign and instantiation notify; cancel never does. */
class TaskNotificationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired NotificationTestSupport support;

    record World(UUID tenant, UUID caseId, UUID milestoneId, UUID actor, UUID bob, UUID carol, UUID blind) {}

    /** actor may create/edit/cancel tasks; bob and carol can view tasks at ALL; blind holds nothing. */
    private World world(String slug) {
        UUID t = fixture.createTenant(slug);
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 5, true));
        UUID milestoneId = fixture.runAsReturning(t, () -> sla.milestoneIdAt(caseId, 0));
        UUID actor = fixture.runAsReturning(t, () -> fixture.createUser(t, "actor@" + slug + ".test"));
        UUID bob = fixture.runAsReturning(t, () -> fixture.createUser(t, "bob@" + slug + ".test"));
        UUID carol = fixture.runAsReturning(t, () -> fixture.createUser(t, "carol@" + slug + ".test"));
        UUID blind = fixture.runAsReturning(t, () -> fixture.createUser(t, "blind@" + slug + ".test"));
        support.grant(t, actor, Map.of(
                PermissionKeys.TASK_MANAGE, Scope.ALL, PermissionKeys.TASK_VIEW, Scope.ALL,
                PermissionKeys.TASK_COMPLETE, Scope.ALL, PermissionKeys.CASE_VIEW, Scope.ALL,
                PermissionKeys.WORKFLOW_VIEW, Scope.ALL, PermissionKeys.USER_VIEW, Scope.ALL));
        for (UUID u : List.of(bob, carol)) support.grant(t, u, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));
        return new World(t, caseId, milestoneId, actor, bob, carol, blind);
    }

    private UUID createAs(World w, UUID assignee) {
        UUID[] id = new UUID[1];
        fixture.runAsUser(w.tenant(), w.actor(), () -> id[0] = tasks.create(w.caseId(), new CreateTaskRequest(
                w.milestoneId(), null, "Chase the KYC pack", null, TaskPriority.MEDIUM, assignee,
                LocalDate.of(2026, 11, 2))).id());
        return id[0];
    }

    private void reassignAs(World w, UUID taskId, UUID assignee) {
        fixture.runAsUser(w.tenant(), w.actor(), () -> tasks.update(taskId, new UpdateTaskRequest(
                "Chase the KYC pack", null, TaskPriority.MEDIUM, assignee, LocalDate.of(2026, 11, 2), w.milestoneId())));
    }

    @Test
    void assigningATaskToSomeoneElseNotifiesThem() {
        var w = world("tn-assign");
        UUID taskId = createAs(w, w.bob());

        var rows = support.rowsFor(w.tenant(), w.bob());
        assertThat(rows).hasSize(1);
        var n = rows.get(0);
        assertThat(n.get("type")).isEqualTo("TASK_ASSIGNED");
        assertThat(n.get("subject_type")).isEqualTo("task");
        assertThat(n.get("subject_id")).isEqualTo(taskId);
        assertThat(n.get("case_id")).isEqualTo(w.caseId());
        UUID customerId = ownerJdbc().queryForObject(
                "select customer_id from onboarding_case where id = ?", UUID.class, w.caseId());
        assertThat(n.get("link_path")).isEqualTo(Links.caseLink("tn-assign", customerId, w.caseId()));
        assertThat(n.get("tone")).isEqualTo("INFO");
        assertThat((String) n.get("title")).startsWith("Task assigned to you:");
        assertThat((String) n.get("body")).contains("Chase the KYC pack").contains("due 2026-11-02");
        assertThat(support.notifications(w.tenant())).hasSize(1);
    }

    @Test
    void selfAssignmentNotifiesNobody() {   // Review Focus 2
        var w = world("tn-self");
        createAs(w, w.actor());
        assertThat(support.notifications(w.tenant())).isEmpty();
    }

    @Test
    void anAssigneeWhoCannotSeeTheTaskIsNotNotified() {
        var w = world("tn-blind");
        UUID taskId = createAs(w, w.blind());
        assertThat(support.notifications(w.tenant())).isEmpty();
        assertThat(ownerJdbc().queryForObject("select count(*) from task where id = ?", Long.class, taskId))
                .isEqualTo(1L);
    }

    /** The narrowest task.view scope: ASSIGNED is assignee_id = recipient, so the new assignee can see it. */
    @Test
    void anAssigneeHoldingTaskViewOnlyAtAssignedScopeIsNotified() {
        var w = world("tn-assigned-scope");
        UUID dana = fixture.runAsReturning(w.tenant(), () -> fixture.createUser(w.tenant(), "dana@tn-assigned-scope.test"));
        support.grant(w.tenant(), dana, Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED));

        UUID taskId = createAs(w, w.bob());
        assertThat(support.rowsFor(w.tenant(), dana)).isEmpty();

        reassignAs(w, taskId, dana);   // the reassignment is visible to the recipient's own grant inside the tx
        assertThat(support.rowsFor(w.tenant(), dana)).hasSize(1);
    }

    @Test
    void reassigningNotifiesOnlyTheNewAssignee() {
        var w = world("tn-reassign");
        UUID taskId = createAs(w, w.bob());
        reassignAs(w, taskId, w.carol());

        assertThat(support.rowsFor(w.tenant(), w.carol())).hasSize(1);
        assertThat(support.rowsFor(w.tenant(), w.bob())).hasSize(1);
        assertThat(support.notifications(w.tenant())).hasSize(2);
    }

    @Test
    void anUnchangedAssigneeNotifiesNobodyAgain() {
        var w = world("tn-unchanged");
        UUID taskId = createAs(w, w.bob());
        reassignAs(w, taskId, w.bob());
        assertThat(support.notifications(w.tenant())).hasSize(1);
    }

    @Test
    void unassigningNotifiesNobody() {
        var w = world("tn-unassign");
        UUID taskId = createAs(w, w.bob());
        reassignAs(w, taskId, null);
        assertThat(support.notifications(w.tenant())).hasSize(1);
    }

    @Test
    void cancellingATaskNotifiesNobody() {
        var w = world("tn-cancel");
        UUID taskId = createAs(w, w.bob());
        fixture.runAsUser(w.tenant(), w.actor(),
                () -> tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.CANCELLED, "No longer needed")));
        assertThat(support.notifications(w.tenant())).hasSize(1);
    }

    @Test
    void anInstantiatedTaskNotifiesTheMilestoneOwner() {
        UUID t = fixture.createTenant("tn-instantiate");
        UUID owner = fixture.runAsReturning(t, () -> fixture.createUser(t, "owner@tn-instantiate.test"));
        support.grant(t, owner, Map.of(PermissionKeys.TASK_VIEW, Scope.ALL));

        UUID caseId = fixture.runAsReturning(t, () -> openCaseWithATaskRequirement(t, owner));

        var rows = support.rowsFor(t, owner);
        assertThat(rows).hasSize(1);
        UUID taskId = ownerJdbc().queryForObject("select id from task where case_id = ?", UUID.class, caseId);
        assertThat(rows.get(0).get("type")).isEqualTo("TASK_ASSIGNED");
        assertThat(rows.get(0).get("subject_id")).isEqualTo(taskId);
        assertThat((String) rows.get(0).get("title")).isEqualTo("Task assigned to you: Prepare");
    }

    @Test
    void anInstantiatedTaskDoesNotNotifyAnOwnerWhoOpenedTheCaseThemselves() {
        UUID t = fixture.createTenant("tn-instantiate-self");
        UUID owner = fixture.createAdministrator(t, "owner@tn-instantiate-self.test");

        UUID[] caseId = new UUID[1];
        fixture.runAsUser(t, owner, () -> caseId[0] = openCaseWithATaskRequirement(t, owner));

        assertThat(ownerJdbc().queryForObject("select assignee_id from task where case_id = ?", UUID.class, caseId[0]))
                .isEqualTo(owner);
        assertThat(support.notifications(t)).isEmpty();
    }

    /** TaskInstantiationTest's shape: the customer's owner becomes the milestone owner, then the task's assignee. */
    private UUID openCaseWithATaskRequirement(UUID tenant, UUID ownerUserId) {
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(), List.of(task("Prepare")))))),
                List.of(), 0L));
        UUID customerId = fixture.createCustomerOwnedBy(tenant, "Acme", ownerUserId);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId),
                "Case " + Uuid7.generate(), Map.of())).id();
    }
}
