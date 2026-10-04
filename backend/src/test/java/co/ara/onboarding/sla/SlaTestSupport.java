package co.ara.onboarding.sla;

import co.ara.onboarding.journey.CaseService;
import co.ara.onboarding.journey.CreateCaseRequest;
import co.ara.onboarding.journey.JourneyFixtures;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.MilestoneRequest;
import co.ara.onboarding.workflow.WorkflowDefinitionRequest.StageRequest;
import co.ara.onboarding.workflow.WriteScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static co.ara.onboarding.workflow.WorkflowFixtures.manual;
import static co.ara.onboarding.workflow.WorkflowFixtures.milestone;

/**
 * Shared arrange/assert helpers for the SLA tests (Tasks 11-22). The builders must run inside
 * {@code fixture.runAs}; the read helpers go through the owner connection and are for assertions
 * only -- never assert RLS through them.
 */
@Component
public class SlaTestSupport {

    private final TenantFixture fixture;
    private final JourneyFixtures journey;
    private final CaseService cases;
    private final co.ara.onboarding.scheduling.TenantJobRunner runner;
    private final SlaSweepService sweep;

    SlaTestSupport(TenantFixture fixture, JourneyFixtures journey, CaseService cases,
                   co.ara.onboarding.scheduling.TenantJobRunner runner, SlaSweepService sweep) {
        this.runner = runner;
        this.sweep = sweep;
        this.fixture = fixture;
        this.journey = journey;
        this.cases = cases;
    }

    /** A stage with auto-advance, an SLA and the given pause eligibility. */
    public static StageRequest slaStage(String key, String name, List<MilestoneRequest> milestones,
                                        Integer slaDays, boolean pausesOnCustomer) {
        return new StageRequest(key, name, null, false, true, true, slaDays, WriteScope.ANY, null,
                null, null, milestones, List.of(), pausesOnCustomer);
    }

    private static MilestoneRequest oneManual(String key) {
        return milestone(key, "Milestone " + key, 1, List.of(), List.of(manual("Do " + key)));
    }

    /** One stage, one MANUAL requirement. */
    public UUID caseWithSla(UUID tenant, int slaDays, boolean pausesOnCustomer) {
        return open(tenant, new WorkflowDefinitionRequest(
                List.of(slaStage("s1", "Stage One", List.of(oneManual("m1")), slaDays, pausesOnCustomer)),
                List.of(), 0L));
    }

    /** The {@link #caseWithSla} workflow (3-day SLA) opened for an existing customer; run inside runAs. */
    public UUID openFor(UUID customerId) {
        UUID versionId = journey.publish(new WorkflowDefinitionRequest(
                List.of(slaStage("s1", "Stage One", List.of(oneManual("m1")), 3, true)), List.of(), 0L));
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId),
                "SLA case", Map.of())).id();
    }

    public UUID twoStageCaseWithSla(UUID tenant, int firstSla, int secondSla) {
        return open(tenant, new WorkflowDefinitionRequest(List.of(
                slaStage("s1", "Stage One", List.of(oneManual("m1")), firstSla, true),
                slaStage("s2", "Stage Two", List.of(oneManual("m2")), secondSla, true)),
                List.of(), 0L));
    }

    public UUID open(UUID tenant, WorkflowDefinitionRequest request) {
        UUID versionId = journey.publish(request);
        UUID customerId = fixture.createCustomer(tenant, "Customer " + Uuid7.generate(), null, null, null);
        return cases.create(new CreateCaseRequest(customerId, journey.templateOf(versionId),
                "SLA case", Map.of())).id();
    }

    public UUID firstRequirementId(UUID caseId) {
        return requirementIdAt(caseId, 0);
    }

    /** The first milestone's first requirement of the stage at {@code stageIndex} (roadmap order). */
    public UUID requirementIdAt(UUID caseId, int stageIndex) {
        return cases.roadmap(caseId).stages().get(stageIndex).milestones().get(0).requirements().get(0).id();
    }

    public UUID milestoneIdAt(UUID caseId, int stageIndex) {
        return cases.roadmap(caseId).stages().get(stageIndex).milestones().get(0).id();
    }

    private static JdbcTemplate owner() {
        return co.ara.onboarding.support.PostgresTestBase.ownerJdbcForSupport();
    }

    /** The open clock's id, or null. */
    public UUID openClockId(UUID caseId) {
        List<UUID> ids = owner().queryForList(
                "select id from sla_clock where case_id = ? and stopped_at is null", UUID.class, caseId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    public List<String> openPauseReasons(UUID clockId) {
        return owner().queryForList(
                "select reason from sla_pause where clock_id = ? and ended_at is null order by reason",
                String.class, clockId);
    }

    public long closedPauses(UUID clockId) {
        return owner().queryForObject(
                "select count(*) from sla_pause where clock_id = ? and ended_at is not null", Long.class, clockId);
    }

    public List<Map<String, Object>> clocks(UUID caseId) {
        return owner().queryForList(
                "select id, stage_id, target_days, started_at, stopped_at, outcome from sla_clock "
                        + "where case_id = ? order by started_at, created_at", caseId);
    }

    /** The two TenantJobRunner runs of spec 6.3, in order: write breaches/escalations/notifications, then email. */
    public void sweepAndEmail(UUID tenant) {
        runner.forTenant("sla-sweep", tenant, t -> sweep.sweep());
        runner.forTenant("sla-email", tenant, t -> sweep.retryUnsentEmail());
    }

    public long escalationCount(UUID tenant) {
        return owner().queryForObject("select count(*) from escalation where tenant_id = ?", Long.class, tenant);
    }
}
