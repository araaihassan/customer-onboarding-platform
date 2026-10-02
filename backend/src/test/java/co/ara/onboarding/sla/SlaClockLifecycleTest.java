package co.ara.onboarding.sla;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.journey.MilestoneService;
import co.ara.onboarding.journey.RequirementService;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.AttributeType;
import co.ara.onboarding.workflow.CloneTemplateRequest;
import co.ara.onboarding.workflow.CustomerTemplateService;
import co.ara.onboarding.workflow.DecidePlanRequest;
import co.ara.onboarding.workflow.PlanDecision;
import co.ara.onboarding.workflow.PlanShapeService;
import co.ara.onboarding.workflow.PublishService;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.AttributeRequest;
import co.ara.onboarding.workflow.WorkflowFixtures;
import co.ara.onboarding.workflow.WorkflowService;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.sla.SlaTestSupport.slaStage;
import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Spec 3.2 / 1.2.1: the clock follows the case lifecycle through the journey port. */
class SlaClockLifecycleTest extends PostgresTestBase {

    @Autowired TenantFixture fixture;
    @Autowired SlaTestSupport sla;
    @Autowired JourneyFixtures journey;
    @Autowired CaseService cases;
    @Autowired RequirementService requirements;
    @Autowired MilestoneService milestones;
    @Autowired WorkflowService workflows;
    @Autowired PublishService publishService;
    @Autowired CustomerTemplateService customerTemplates;
    @Autowired PlanShapeService planShapeService;
    @Autowired WorkflowVersionRepository versionRepository;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void enteringAStageWithAnSlaStartsAClock() {
        UUID t = fixture.createTenant("sla-start");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));

        var clocks = sla.clocks(caseId);
        assertThat(clocks).hasSize(1);
        assertThat(clocks.get(0).get("stopped_at")).isNull();
        assertThat(clocks.get(0).get("target_days")).isEqualTo(3);
        UUID current = fixture.runAsReturning(t, () -> cases.get(caseId).currentStageId());
        assertThat(clocks.get(0).get("stage_id")).isEqualTo(current);
    }

    @Test
    void aStageWithoutAnSlaGetsNoClock() {
        UUID t = fixture.createTenant("sla-none");
        UUID caseId = fixture.runAsReturning(t, () -> sla.open(t, new WorkflowDefinitionRequest(
                List.of(slaStage("s1", "S1", List.of(milestone("m1", "M", 1, List.of(), List.of(manual("x")))),
                        null, true)), List.of(), 0L)));

        assertThat(sla.clocks(caseId)).isEmpty();
    }

    @Test
    void advancingStopsTheClockAsMetAndStartsTheNext() {
        UUID t = fixture.createTenant("sla-advance");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID id = sla.twoStageCaseWithSla(t, 3, 2);
            requirements.satisfy(sla.requirementIdAt(id, 0), null, null);
            return id;
        });

        var clocks = sla.clocks(caseId);
        assertThat(clocks).hasSize(2);
        assertThat(clocks.get(0).get("outcome")).isEqualTo("MET");
        assertThat(clocks.get(0).get("stopped_at")).isNotNull();
        assertThat(clocks.get(1).get("stopped_at")).isNull();
        assertThat(clocks.get(1).get("target_days")).isEqualTo(2);
    }

    @Test
    void completingTheCaseStopsTheLastClock() {
        UUID t = fixture.createTenant("sla-complete");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID id = sla.caseWithSla(t, 3, true);
            requirements.satisfy(sla.firstRequirementId(id), null, null);
            return id;
        });

        assertThat(sla.clocks(caseId)).hasSize(1);
        assertThat(sla.clocks(caseId).get(0).get("outcome")).isEqualTo("MET");
        assertThat(sla.openClockId(caseId)).isNull();
    }

    @Test
    void anOverdueExitIsRecordedBreached() {
        UUID t = fixture.createTenant("sla-breach");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID id = sla.caseWithSla(t, 1, true);
            clock.advance(Duration.ofDays(4));
            requirements.satisfy(sla.firstRequirementId(id), null, null);
            return id;
        });

        assertThat(sla.clocks(caseId).get(0).get("outcome")).isEqualTo("BREACHED");
    }

    @Test
    void aSkippedStageGetsNoClock() {
        UUID t = fixture.createTenant("sla-skip");
        UUID caseId = fixture.runAsReturning(t, () -> {
            var attrs = List.of(new AttributeRequest("segment", "Segment", AttributeType.ENUM, true,
                    List.of("SMB", "ENTERPRISE")));
            var base = new WorkflowDefinitionRequest(List.of(
                    slaStage("s1", "One", List.of(milestone("m1", "M1", 1, List.of(), List.of(manual("a")))), 3, true),
                    slaStage("s2", "Two", List.of(milestone("m2", "M2", 1, List.of(), List.of(manual("b")))), 3, true),
                    slaStage("s3", "Three", List.of(milestone("m3", "M3", 1, List.of(), List.of(manual("c")))), 3, true)),
                    attrs, 0L);
            var request = WorkflowFixtures.withBranch(base, "s1", "segment", "SMB", "s3");
            UUID versionId = journey.publish(request);
            UUID customerId = fixture.createCustomer(t, "C", null, null, null);
            UUID id = cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId), "SLA case",
                    Map.of("segment", "SMB"))).id();
            requirements.satisfy(sla.requirementIdAt(id, 0), null, null);
            return id;
        });

        var stageIds = sla.clocks(caseId).stream().map(c -> c.get("stage_id")).toList();
        assertThat(stageIds).hasSize(2);
        UUID s2 = fixture.runAsReturning(t, () -> cases.roadmap(caseId).stages().get(1).id());
        assertThat(stageIds).doesNotContain(s2);
    }

    @Test
    void holdingPausesAndResumingCloses() {
        UUID t = fixture.createTenant("sla-hold");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));

        fixture.runAs(t, () -> cases.hold(caseId, "waiting"));
        UUID clockId = sla.openClockId(caseId);
        assertThat(sla.openPauseReasons(clockId)).containsExactly("CASE_HOLD");

        fixture.runAs(t, () -> cases.resume(caseId));
        assertThat(sla.openPauseReasons(clockId)).isEmpty();
        assertThat(sla.closedPauses(clockId)).isEqualTo(1);
    }

    @Test
    void aCustomerTemplateCaseStartsPaused() {
        UUID t = fixture.createTenant("sla-customer-template");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID catalogueTemplateId = workflows.createTemplate("SLA Onboarding " + Uuid7.generate(), "").id();
            UUID draftId = workflows.createDraft(catalogueTemplateId);
            workflows.replaceDraft(draftId, new WorkflowDefinitionRequest(List.of(
                    slaStage("s1", "Delivery", List.of(milestone("m1", "Kickoff", 2, List.of(),
                            List.of(manual("Sign up")))), 5, true)), List.of(), 0L));
            publishService.publish(draftId);

            UUID customerId = fixture.createCustomer(t, "Customer " + Uuid7.generate(), null, null, null);
            var clone = customerTemplates.clone(catalogueTemplateId,
                    new CloneTemplateRequest(customerId, "Clone " + Uuid7.generate()));
            UUID cloneVersionId = versionRepository.findByTemplateIdOrderByVersionNoDesc(clone.id()).get(0).getId();
            publishService.publish(cloneVersionId);
            UUID contactId = fixture.createContact(t, customerId, "sponsor+" + Uuid7.generate() + "@sla.example");
            planShapeService.submit(cloneVersionId);
            planShapeService.decide(cloneVersionId, new DecidePlanRequest(PlanDecision.APPROVED, "Approved", contactId));
            return cases.create(new CreateCaseRequest(customerId, clone.id(), "SLA clone case", Map.of())).id();
        });

        UUID clockId = sla.openClockId(caseId);
        assertThat(clockId).isNotNull();
        assertThat(sla.openPauseReasons(clockId)).containsExactly("CASE_HOLD");
        Instant started = ownerJdbc().queryForObject(
                "select started_at from sla_clock where id = ?", Timestamp.class, clockId).toInstant();
        Instant pauseStart = ownerJdbc().queryForObject(
                "select started_at from sla_pause where clock_id = ?", Timestamp.class, clockId).toInstant();
        assertThat(Duration.between(started, pauseStart).abs()).isLessThan(Duration.ofSeconds(1));
    }

    /** Review focus 4: a reopen into an earlier stage of a completed case starts a fresh clock. */
    @Test
    void reopeningIntoAnEarlierStageStartsAFreshClock() {
        UUID t = fixture.createTenant("sla-reopen-earlier");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID id = sla.twoStageCaseWithSla(t, 3, 2);
            requirements.satisfy(sla.requirementIdAt(id, 0), null, null);
            requirements.satisfy(sla.requirementIdAt(id, 1), null, null);   // case COMPLETED
            return id;
        });
        var before = sla.clocks(caseId);
        assertThat(before).hasSize(2);
        assertThat(sla.openClockId(caseId)).isNull();

        fixture.runAs(t, () -> milestones.reopen(sla.milestoneIdAt(caseId, 0), "rework"));

        var after = sla.clocks(caseId);
        assertThat(after).hasSize(3);
        assertThat(after.get(0)).isEqualTo(before.get(0));            // old stopped clocks stay as recorded
        assertThat(after.get(1)).isEqualTo(before.get(1));
        assertThat(after.get(2).get("stopped_at")).isNull();
        assertThat(after.get(2).get("stage_id")).isEqualTo(before.get(0).get("stage_id"));
    }

    @Test
    void reopeningWithinTheCurrentStageLeavesTheClockAlone() {
        UUID t = fixture.createTenant("sla-reopen-same");
        UUID caseId = fixture.runAsReturning(t, () -> {
            UUID id = sla.open(t, new WorkflowDefinitionRequest(List.of(
                    slaStage("s1", "S1", List.of(
                            milestone("m1", "M1", 1, List.of(), List.of(manual("a"))),
                            milestone("m2", "M2", 1, List.of(), List.of(manual("b")))), 3, true)),
                    List.of(), 0L));
            requirements.satisfy(sla.requirementIdAt(id, 0), null, null);   // m1 done, m2 still open
            return id;
        });
        UUID clockBefore = sla.openClockId(caseId);
        assertThat(clockBefore).isNotNull();

        fixture.runAs(t, () -> milestones.reopen(sla.milestoneIdAt(caseId, 0), "rework"));

        assertThat(sla.openClockId(caseId)).isEqualTo(clockBefore);
        assertThat(sla.clocks(caseId)).hasSize(1);
    }

    @Test
    void everyClockWriteRollsBackWithItsCause() {
        UUID t = fixture.createTenant("sla-rollback");
        UUID caseId = fixture.runAsReturning(t, () -> sla.caseWithSla(t, 3, true));

        assertThatThrownBy(() -> fixture.runAs(t, () ->
                new TransactionTemplate(txManager).executeWithoutResult(status -> {
                    cases.hold(caseId, "x");
                    status.setRollbackOnly();
                }))).isInstanceOf(UnexpectedRollbackException.class);

        assertThat(sla.openPauseReasons(sla.openClockId(caseId))).isEmpty();
    }
}
