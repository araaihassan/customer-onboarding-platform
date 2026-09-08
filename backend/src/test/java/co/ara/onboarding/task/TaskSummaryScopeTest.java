package co.ara.onboarding.task;

import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RoleService;
import co.ara.onboarding.authz.Scope;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneService;
import co.ara.onboarding.journey.TaskDirectory;
import co.ara.onboarding.journey.TaskSummary;
import co.ara.onboarding.journey.UpdateMilestoneRequest;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sub-project 3A Task 5: journey.MilestoneRoadmapView.taskSummary was
 * computed by TaskDirectoryAdapter.summaryFor over EVERY task on a milestone
 * regardless of the reader's task.view scope -- an ASSIGNED-scoped reader
 * (Sales Representative, Service Provider, Business Partner in the seeded
 * templates) saw a count that included tasks assigned to somebody else, tasks
 * they cannot open. This is exactly the aggregate-only leak shape spec §6.4
 * refuses to repeat for the later programme rollup; closing it here sets the
 * precedent that fix is measured against.
 *
 * The milestone owner is reassigned to the ASSIGNED-scoped reader first --
 * MilestoneService.update's own ensureAssigneeParticipant makes them a real
 * CaseParticipant (RelationshipType.ASSIGNEE), the ordinary, product way a
 * personal relationship to a case is established -- so the reader's own
 * TaskService.forCase(caseId) read (gated CASE_VIEW-shaped under TASK_VIEW,
 * same as the summary) succeeds too, and both reads can be compared directly
 * rather than one 404ing for an unrelated reason.
 */
class TaskSummaryScopeTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired MilestoneService milestones;
    @Autowired TaskService taskService;
    @Autowired TaskDirectory taskDirectory;
    @Autowired RoleService roles;

    @Test
    void summaryCountsOnlyTasksTheReaderCanOpen() {
        UUID tenant = fixture.createTenant("task-summary-scope");
        UUID[] salesRep = new UUID[1];
        UUID[] caseId = new UUID[1];
        UUID[] milestoneId = new UUID[1];

        fixture.runAs(tenant, () -> {
            salesRep[0] = fixture.createUser(tenant, "sales-rep@example.com");
            grant(salesRep[0], Map.of(PermissionKeys.TASK_VIEW, Scope.ASSIGNED));

            UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
            caseId[0] = cases.create(new CreateCaseRequest(
                    customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(),
                    Map.of())).id();
            milestoneId[0] = cases.roadmap(caseId[0]).stages().get(0).milestones().get(0).id();

            // Makes salesRep a real CaseParticipant(ASSIGNEE) -- the ordinary way a
            // personal relationship to the case is established -- so their own
            // TaskService.forCase read is comparable, not blocked for an unrelated
            // reason (case-level scope, not task-level scope).
            milestones.update(milestoneId[0], new UpdateMilestoneRequest(salesRep[0], null));

            UUID otherUser = fixture.createUser(tenant, "other-user@example.com");

            taskService.create(caseId[0], new CreateTaskRequest(
                    milestoneId[0], null, "Mine", null, TaskPriority.MEDIUM, salesRep[0], null));
            taskService.create(caseId[0], new CreateTaskRequest(
                    milestoneId[0], null, "Theirs", null, TaskPriority.MEDIUM, otherUser, null));
        });

        fixture.runAsUser(tenant, salesRep[0], () -> {
            TaskSummary summary = taskDirectory.summaryFor(List.of(milestoneId[0])).get(milestoneId[0]);

            // salesRep holds task.view at ASSIGNED. "Theirs" is invisible to them --
            // and must be invisible in the COUNT too, not merely unopenable.
            assertThat(summary.total()).isEqualTo(1);
            assertThat(summary.open()).isEqualTo(1);
            assertThat(taskService.forCase(caseId[0])).hasSize(1);
        });
    }

    private void grant(UUID userId, Map<String, Scope> grants) {
        UUID role = roles.createRole("Fixture Role " + Uuid7.generate(), "", grants);
        roles.assignRole(userId, role);
    }
}
