package co.ara.onboarding.task;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.TaskSummary;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.StatementCounter;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 21: TaskDirectoryAdapter is the first real implementation of
 * journey.TaskDirectory (Task 14's port). The roadmap now carries an
 * open/total task count per milestone, resolved in exactly one SQL query
 * regardless of how many milestones the roadmap has -- the whole reason the
 * port's signature takes a Collection<UUID> instead of one id at a time.
 *
 * The one-query test needs its own @DynamicPropertySource wiring
 * CountingStatementInspector via the hibernate.session_factory.statement_inspector
 * property -- a different property set than every other test class, so this
 * class gets its own cached Spring context rather than sharing PostgresTestBase's
 * default one. See CountingStatementInspector/StatementCounter for why the
 * recorded SQL lives on a static field rather than an injected bean.
 */
class TaskDirectoryTest extends PostgresTestBase {

    @DynamicPropertySource
    static void statementInspector(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
                () -> "co.ara.onboarding.support.CountingStatementInspector");
    }

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;

    @Test
    void theRoadmapCarriesOpenAndTotalTaskCountsPerMilestone() {
        UUID tenant = fixture.createTenant("task-dir-counts");
        fixture.runAs(tenant, () -> {
            UUID caseId = openSimpleCase(tenant);
            UUID milestoneId = cases.roadmap(caseId).stages().get(0).milestones().get(0).id();

            UUID completedTaskId = createAdHocTask(caseId, milestoneId);
            createAdHocTask(caseId, milestoneId);
            createAdHocTask(caseId, milestoneId);
            tasks.changeStatus(completedTaskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));

            var milestone = cases.roadmap(caseId).stages().get(0).milestones().get(0);
            assertThat(milestone.taskSummary().total()).isEqualTo(3);
            assertThat(milestone.taskSummary().open()).isEqualTo(2);   // one completed
        });
    }

    /** The port takes a collection precisely so a roadmap is not N queries. */
    @Test
    void aRoadmapOfManyMilestonesIssuesOneTaskQuery() {
        UUID tenant = fixture.createTenant("task-dir-onequery");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithTwelveMilestones(tenant);

            var counter = StatementCounter.reset();
            cases.roadmap(caseId);
            assertThat(counter.countMatching("from task")).isEqualTo(1);
        });
    }

    @Test
    void aMilestoneWithNoTasksReportsZeroNotNull() {
        UUID tenant = fixture.createTenant("task-dir-empty");
        fixture.runAs(tenant, () -> {
            UUID caseId = openSimpleCase(tenant);

            var milestone = cases.roadmap(caseId).stages().get(0).milestones().get(0);
            assertThat(milestone.taskSummary()).isEqualTo(new TaskSummary(0, 0));
        });
    }

    private UUID createAdHocTask(UUID caseId, UUID milestoneId) {
        return tasks.create(caseId, new CreateTaskRequest(
                milestoneId, null, "Fixture Task " + Uuid7.generate(), null,
                TaskPriority.MEDIUM, null, null)).id();
    }

    private UUID openSimpleCase(UUID tenant) {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    /** One stage, twelve milestones -- least-effort way to get twelve without twelve separate stages. */
    private UUID openCaseWithTwelveMilestones(UUID tenant) {
        List<WorkflowDefinitionRequest.MilestoneRequest> twelveMilestones = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelveMilestones.add(milestone("m" + i, "Milestone " + i, 1, List.of(), List.of(manual("Do it"))));
        }
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", twelveMilestones)), List.of(), 0L);
        UUID versionId = journey.publish(request);
        UUID templateId = journey.templateOf(versionId);

        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }
}
