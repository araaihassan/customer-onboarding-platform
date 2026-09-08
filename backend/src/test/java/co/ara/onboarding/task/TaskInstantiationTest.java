package co.ara.onboarding.task;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static co.ara.onboarding.workflow.WorkflowFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 19: a workflow requirement of kind TASK now produces a real Task row
 * the moment its case opens -- the seam sub-project 2 deliberately left open
 * (RequirementRoadmapView's own kind field, and CaseRequirementView's
 * satisfiedRef/satisfiedRefType, exist precisely so a task can satisfy a
 * requirement by reference instead of a plain manual check-off).
 *
 * Mirrors the brief's three required tests verbatim in intent; adapted to
 * this codebase's TaskRepository/entity accessors rather than a TaskView
 * record with tasks.forCase(...), since forCase is TaskService's own gated
 * read and this task's instantiation path is deliberately NOT reached
 * through a *Service -- see TaskLifecycleAdapter's own javadoc.
 */
class TaskInstantiationTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired TaskRepository tasks;

    @Test
    void aRequirementOfKindTaskProducesATaskWhenTheCaseOpens() {
        UUID tenant = fixture.createTenant("task-inst-basic");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWhoseFirstRequirementIsKindTask(tenant, null);

            assertThat(tasks.findByCaseId(caseId)).singleElement()
                    .satisfies(t -> {
                        assertThat(t.getRequirementId()).isNotNull();
                        assertThat(t.getTitle()).isEqualTo("Collect KYC pack");
                    });
        });
    }

    /**
     * Q15: the OWNER participant is the default milestone owner. An
     * instantiated task arriving unassigned would put work in nobody's
     * queue at the exact moment the journey opens.
     */
    @Test
    void anInstantiatedTaskDefaultsToTheMilestoneOwner() {
        UUID tenant = fixture.createTenant("task-inst-owner");
        fixture.runAs(tenant, () -> {
            UUID owner = fixture.createUser(tenant, "owner@example.com");
            UUID caseId = openCaseWhoseFirstRequirementIsKindTask(tenant, owner);

            assertThat(tasks.findByCaseId(caseId).get(0).getAssigneeId()).isEqualTo(owner);
        });
    }

    @Test
    void requirementsOfOtherKindsProduceNoTask() {
        UUID tenant = fixture.createTenant("task-inst-manual-only");
        fixture.runAs(tenant, () -> {
            // publishedTemplate() is a single stage/milestone/MANUAL requirement,
            // no TASK requirement anywhere in the graph.
            UUID templateId = journey.publishedTemplate();
            UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
            UUID caseId = cases.create(new CreateCaseRequest(
                    customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();

            assertThat(tasks.findByCaseId(caseId)).isEmpty();
        });
    }

    private UUID openCaseWhoseFirstRequirementIsKindTask(UUID tenant, UUID ownerUserId) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(task("Collect KYC pack")))))),
                List.of(), 0L);
        UUID versionId = journey.publish(request);
        UUID templateId = journey.templateOf(versionId);

        UUID customerId = ownerUserId == null
                ? fixture.createCustomer(tenant, "Acme", null, null, null)
                : fixture.createCustomerOwnedBy(tenant, "Acme", ownerUserId);

        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }
}
