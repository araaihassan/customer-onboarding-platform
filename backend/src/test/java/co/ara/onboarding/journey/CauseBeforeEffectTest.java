package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditEventView;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.task.TaskRepository;
import co.ara.onboarding.task.TaskService;
import co.ara.onboarding.task.TaskStatus;
import co.ara.onboarding.task.TaskStatusRequest;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static co.ara.onboarding.workflow.WorkflowFixtures.stage;
import static co.ara.onboarding.workflow.WorkflowFixtures.task;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An action's own audit event must be recorded BEFORE the events describing
 * what that action triggered. AuditRecorder stamps occurredAt from the clock,
 * so call order IS timeline order -- see its javadoc for the full rule.
 *
 * Every one of these assertions failed before the fix: nine call sites across
 * five journey services recorded their cause after engine.reconcile(), so a
 * newest-first timeline showed each cause sitting above its own effects. The
 * user's report was "milestones completed before the case is assigned to a
 * user, which is wrong" -- read off the screen, because no test looked at the
 * relative order of two DIFFERENT actions.
 *
 * These assert on ORDER ONLY, never on the number of events, so a sub-project
 * that adds a new event to any of these paths does not have to edit this file.
 */
class CauseBeforeEffectTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired CaseRepository caseRepository;
    @Autowired RequirementService requirements;
    @Autowired TimelineService timeline;
    @Autowired TaskService tasks;
    @Autowired TaskRepository taskRepository;
    @Autowired PlanRevisionService planRevisionService;
    @Autowired PlanShapeService planShapeService;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired WorkflowVersionRepository versionRepository;

    @Test
    void creatingACaseIsRecordedBeforeTheStageEntryAndMilestonesItCauses() {
        UUID tenant = fixture.createTenant("cbe-create");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);

            // Oldest-first: the case is opened, and only then does anything
            // happen inside it.
            assertThat(chronological(caseId))
                    .startsWith("case.created")
                    .containsSubsequence("case.created", "case.stage_entered");
        });
    }

    @Test
    void satisfyingARequirementIsRecordedBeforeTheMilestoneItCompletes() {
        UUID tenant = fixture.createTenant("cbe-satisfy");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);

            // The completion is a CONSEQUENCE of the satisfaction, so it cannot
            // precede it. Before the fix these were the other way round.
            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "milestone.completed");
        });
    }

    @Test
    void aCompletedCaseIsRecordedAfterTheRequirementThatCompletedIt() {
        UUID tenant = fixture.createTenant("cbe-complete");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            requirements.satisfy(firstRequirementId(caseId), null, null);   // completes the case

            assertThat(chronological(caseId))
                    .containsSubsequence("requirement.satisfied", "case.completed")
                    .endsWith("case.completed");
        });
    }

    /**
     * Hold and resume are the ordering case that does NOT go through a
     * reconcile-writes-effects path in this fixture, so it is here as the
     * control: it should have read correctly before the fix and still does.
     */
    @Test
    void holdAndResumeReadInTheOrderTheyHappened() {
        UUID tenant = fixture.createTenant("cbe-resume");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCase(tenant);
            cases.hold(caseId, "pausing");
            cases.resume(caseId);

            assertThat(chronological(caseId))
                    .containsSubsequence("case.created", "case.held", "case.resumed");
        });
    }

    /**
     * Task 25: closes the task.created gap AuditActions' own comment named as
     * still open. TaskInstantiation runs strictly between CaseService.create's
     * own CASE_CREATED record and engine.reconcile (see that call site's
     * comment) -- so a case whose workflow declares a TASK-kind requirement
     * must show its instantiated task's own event after the case's, never
     * before it.
     */
    @Test
    void taskCreationIsRecordedBeforeTheEventsItCauses() {
        UUID tenant = fixture.createTenant("cbe-task-created");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithATaskRequirement(tenant);

            assertThat(chronological(caseId))
                    .containsSubsequence("case.created", "task.created");
        });
    }

    /**
     * The full causal chain sub-project 3 introduces, asserted end to end:
     * TaskService.changeStatus records TASK_STATUS_CHANGED before calling the
     * already-gated RequirementService.satisfy, which itself records
     * requirement.satisfied before the reconcile that completes the milestone
     * -- sub-project 2's own already-proven ordering, chained onto a new
     * caller rather than reimplemented.
     */
    @Test
    void completingATaskIsRecordedBeforeTheRequirementItSatisfies() {
        UUID tenant = fixture.createTenant("cbe-task-complete");
        fixture.runAs(tenant, () -> {
            UUID caseId = openCaseWithATaskRequirement(tenant);
            UUID taskId = taskRepository.findByCaseId(caseId).get(0).getId();

            tasks.changeStatus(taskId, new TaskStatusRequest(TaskStatus.COMPLETED, null));

            assertThat(chronological(caseId))
                    .containsSubsequence("task.status_changed", "requirement.satisfied",
                            "milestone.completed");
        });
    }

    /**
     * Sub-project 3A Task 25 (QA Q22/Q23 gate 2): {@code
     * PlanRevisionService.decide}'s own {@code plan.revision_decided} record
     * must precede {@code case.resumed} -- the event {@code CaseService.resume}
     * itself records -- on a case's first-ever approved schedule revision. This
     * is the guard whose actual subject this ordering is, per this task's own
     * brief: NOT a {@code PlanRevisionTest} concern, this file's.
     */
    @Test
    void theRevisionDecisionIsRecordedBeforeTheResumeItCauses() {
        UUID tenant = fixture.createTenant("cbe-plan-revision");
        fixture.runAs(tenant, () -> {
            UUID caseId = openHeldCaseOnApprovedCustomerTemplate(tenant);

            PlanRevisionView rev = planRevisionService.issue(caseId, new IssueRevisionRequest("v1"));
            planRevisionService.decide(rev.id(),
                    new DecidePlanRequest(PlanDecision.APPROVED, "Approved", null));

            assertThat(chronological(caseId))
                    .containsSubsequence("plan.revision_decided", "case.resumed");
        });
    }

    /**
     * A customer-tier clone (single stage, single portal-visible milestone),
     * published, shape-submitted and APPROVED, cased, and then forced into
     * ON_HOLD directly against the repository -- Task 26 (not yet built) is
     * what will eventually make case creation on a customer template start this
     * way in production; seeded directly here rather than waiting on that
     * wiring, per this task's own pre-flight ruling. Same shape as {@code
     * PlanRevisionTest.openApprovedCustomerCase}/{@code
     * openHeldCaseOnCustomerTemplate}, duplicated here rather than shared: this
     * class runs everything as the tenant's fixture superuser and has no need
     * for PlanRevisionTest's narrow-scoped pm/am actors.
     */
    private UUID openHeldCaseOnApprovedCustomerTemplate(UUID tenant) {
        UUID catalogueTemplateId = workflows.createTemplate("Fixture Onboarding " + Uuid7.generate(), "").id();
        UUID catalogueDraftId = workflows.createDraft(catalogueTemplateId);
        workflows.replaceDraft(catalogueDraftId, new WorkflowDefinitionRequest(
                List.of(stage("s1", "Delivery", List.of(
                        milestone("m1", "Kickoff", 2, List.of(), List.of(manual("Sign up")))))),
                List.of(), 0L));
        publishService.publish(catalogueDraftId);

        UUID customerId = fixture.createCustomer(tenant, "Plan Revision Customer " + Uuid7.generate(),
                null, null, null);
        var clone = customerTemplates.clone(catalogueTemplateId,
                new CloneTemplateRequest(customerId, "Plan Revision Clone " + Uuid7.generate()));
        UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
        publishService.publish(cloneVersionId);

        UUID contactId = fixture.createContact(tenant, customerId,
                "sponsor+" + Uuid7.generate() + "@cause-before-effect.example");
        planShapeService.submit(cloneVersionId);
        planShapeService.decide(cloneVersionId,
                new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));

        UUID caseId = cases.create(new CreateCaseRequest(customerId, clone.id(),
                "Plan Revision Case " + Uuid7.generate(), Map.of())).id();

        Case c = caseRepository.findById(caseId).orElseThrow();
        c.setStatus(CaseStatus.ON_HOLD);
        c.setHeldAt(Instant.now());
        caseRepository.saveAndFlush(c);

        return caseId;
    }

    /**
     * A single stage/milestone whose one requirement is kind TASK, published
     * and opened -- same shape as TaskInstantiationTest's own
     * openCaseWhoseFirstRequirementIsKindTask, needed here too since
     * publishedTemplate()'s own requirement is MANUAL and would never produce
     * a task.created event to assert on.
     */
    private UUID openCaseWithATaskRequirement(UUID tenant) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                List.of(stage("s1", "Stage One", List.of(
                        milestone("m1", "Milestone One", 1, List.of(),
                                List.of(task("Collect KYC pack")))))),
                List.of(), 0L);
        UUID versionId = journey.publish(request);
        UUID templateId = journey.templateOf(versionId);
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(
                customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    /**
     * The timeline read is oldest-first (see AuditEventRepository), so this is
     * the order things actually happened, unreversed. Kept as a named helper
     * rather than inlined: if the read direction is ever flipped back, this is
     * the single place these order assertions need to adapt.
     */
    private List<String> chronological(UUID caseId) {
        return timeline.forCase(caseId, Pageable.ofSize(100)).getContent().stream()
                .map(AuditEventView::action)
                .toList();
    }

    private UUID openCase(UUID tenant) {
        UUID templateId = journey.publishedTemplate();
        UUID customerId = fixture.createCustomer(tenant, "Acme", null, null, null);
        return cases.create(new CreateCaseRequest(customerId, templateId, "Fixture Case " + Uuid7.generate(), Map.of())).id();
    }

    private UUID firstRequirementId(UUID caseId) {
        return cases.roadmap(caseId).stages().get(0).milestones().get(0).requirements().get(0).id();
    }
}
