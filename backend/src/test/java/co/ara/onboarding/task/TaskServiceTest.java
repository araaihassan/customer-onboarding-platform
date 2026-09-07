package co.ara.onboarding.task;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16: create ad-hoc, read, and the write-path guards. Every id TaskService
 * takes from a URL or a request body -- caseId, milestoneId -- is resolved
 * through AuthorizedQuery before a write, the same escalation shape
 * RequirementService's own doc comment names, and the one that bit
 * sub-project 1 three times (contact creation, role assignment, invitation
 * issuance).
 */
class TaskServiceTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;

    @Test
    void anAdHocTaskHasNoRequirementAndDoesNotTouchProgress() {
        UUID tenant = fixture.createTenant("task-adhoc");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID assigneeId = fixture.createUser(tenant, "assignee@example.com");

            var before = cases.get(caseId).progressPercent();
            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.MEDIUM, assigneeId, LocalDate.now().plusDays(3))).id();

            assertThat(tasks.get(taskId).requirementId()).isNull();
            assertThat(cases.get(caseId).progressPercent()).isEqualTo(before);
        });
    }

    @Test
    void aTaskInAnotherTenantIsA404() {
        UUID tenantA = fixture.createTenant("task-tenant-a");
        UUID tenantB = fixture.createTenant("task-tenant-b");
        var taskId = new UUID[1];
        fixture.runAs(tenantA, () -> {
            UUID caseId = simpleCase(tenantA);
            UUID milestoneId = firstMilestone(caseId);
            taskId[0] = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Tenant A task", null,
                    TaskPriority.LOW, null, null)).id();
        });

        // Never assert inside the runAs lambda -- see CaseCreationTest's own note:
        // catching there leaves the transaction rollback-only and surfaces
        // UnexpectedRollbackException instead of the exception under test.
        assertThatThrownBy(() -> fixture.runAs(tenantB, () -> tasks.get(taskId[0])))
                .isInstanceOf(NoSuchElementException.class);
    }

    /** The escalation shape that bit sub-project 1 three times. */
    @Test
    void aMilestoneIdFromTheRequestBodyIsResolvedBeforeItIsWritten() {
        UUID tenantA = fixture.createTenant("task-escalation-a");
        UUID tenantB = fixture.createTenant("task-escalation-b");
        var caseId = new UUID[1];
        var anotherTenantsMilestoneId = new UUID[1];
        fixture.runAs(tenantA, () -> caseId[0] = simpleCase(tenantA));
        fixture.runAs(tenantB, () -> anotherTenantsMilestoneId[0] = firstMilestone(simpleCase(tenantB)));

        assertThatThrownBy(() -> fixture.runAs(tenantA, () -> tasks.create(caseId[0],
                new CreateTaskRequest(anotherTenantsMilestoneId[0], null, "Escalation attempt", null,
                        TaskPriority.LOW, null, null))))
                .isInstanceOf(NoSuchElementException.class);
    }

    private UUID simpleCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, journey.publishedTemplate(), "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstMilestone(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).id();
    }
}
