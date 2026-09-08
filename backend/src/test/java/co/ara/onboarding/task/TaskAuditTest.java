package co.ara.onboarding.task;

import co.ara.onboarding.audit.AuditEvent;
import co.ara.onboarding.audit.AuditEventRepository;
import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
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
 * Sub-project 3A Task 3: design spec §5.5 of sub-project 3 named seven task
 * audit actions; only five existed before this task. task.assigned had no
 * constant and nothing recorded a reassignment, and TaskService.create's
 * ad-hoc path recorded no task.created at all -- only TaskInstantiation's
 * requirement-instantiated path did (see AuditActions.TASK_CREATED's own
 * comment).
 *
 * The third test is the one that actually matters: task.assigned must fire
 * only on a TRANSITION of the assignee (old != new), never on every update
 * that happens to carry the same assignee unchanged -- the same distinction
 * CONTACT_DEACTIVATED draws against a plain phone-number correction
 * elsewhere in this codebase.
 */
class TaskAuditTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskService tasks;
    @Autowired AuditEventRepository auditEvents;

    @Test
    void adHocCreateRecordsTaskCreated() {
        UUID tenant = fixture.createTenant("task-audit-created");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);

            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.MEDIUM, null, null)).id();

            assertThat(auditActionsFor(taskId)).contains("task.created");
        });
    }

    @Test
    void reassignmentRecordsTaskAssigned() {
        UUID tenant = fixture.createTenant("task-audit-assigned");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID otherUserId = fixture.createUser(tenant, "other@example.com");

            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.MEDIUM, null, null)).id();

            tasks.update(taskId, new UpdateTaskRequest(
                    "Chase legal re: NDA", null, TaskPriority.MEDIUM, otherUserId, null, milestoneId));

            assertThat(auditActionsFor(taskId)).contains("task.assigned");
        });
    }

    @Test
    void anUpdateThatDoesNotChangeTheAssigneeRecordsNoAssignment() {
        UUID tenant = fixture.createTenant("task-audit-no-reassign");
        fixture.runAs(tenant, () -> {
            UUID caseId = simpleCase(tenant);
            UUID milestoneId = firstMilestone(caseId);
            UUID assigneeId = fixture.createUser(tenant, "assignee@example.com");

            UUID taskId = tasks.create(caseId, new CreateTaskRequest(
                    milestoneId, null, "Chase legal re: NDA", null,
                    TaskPriority.MEDIUM, assigneeId, null)).id();

            tasks.update(taskId, new UpdateTaskRequest(
                    "Renamed", null, TaskPriority.HIGH, assigneeId, null, milestoneId));

            assertThat(auditActionsFor(taskId)).doesNotContain("task.assigned");
        });
    }

    /**
     * Every task audit action so far (TASK_CREATED, TASK_STATUS_CHANGED,
     * TASK_CANCELLED) is recorded against "onboarding_case"/caseId, not a
     * "task" resource type of its own -- so this filters the tenant's audit
     * trail down to events whose payload names this specific task, the same
     * way a real caller would disambiguate one task's history from another's
     * on the same case.
     */
    private List<String> auditActionsFor(UUID taskId) {
        return auditEvents.findAll().stream()
                .filter(e -> e.getPayload() != null && e.getPayload().contains(taskId.toString()))
                .map(AuditEvent::getAction)
                .toList();
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
