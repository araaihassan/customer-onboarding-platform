package co.ara.onboarding.journey;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.RequirementKind;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import co.ara.onboarding.workflow.TemplateStatus;
import co.ara.onboarding.workflow.VersionStatus;
import co.ara.onboarding.workflow.WorkflowTemplate;
import co.ara.onboarding.workflow.WorkflowTemplateRepository;
import co.ara.onboarding.workflow.WorkflowVersion;
import co.ara.onboarding.workflow.WorkflowVersionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CaseEngine and its repositories are called directly, not through CaseService or
 * RequirementService (Tasks 13 and 16) -- neither exists yet, since CaseEngine is
 * written first (see the amendment note above Task 13/14 in the plan). The hazard
 * under test does not depend on either: it is that two transactions each satisfying
 * one of a milestone's two requirements, without a row lock serialising them, can
 * each read the other's not-yet-committed state and each write back a conclusion
 * that clobbers the other's -- a lost update, not (yet) a double stage-advance,
 * because Task 15's stage-advance does not exist yet either. The row lock this task
 * adds closes the same hazard Task 15 will build on.
 */
class ReconcileConcurrencyTest extends PostgresTestBase {

    @Autowired CaseEngine engine;
    @Autowired CaseRepository caseRepository;
    @Autowired MilestoneRepository milestones;
    @Autowired RequirementRepository requirements;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;
    @Autowired MilestoneDefinitionRepository milestoneDefinitions;
    @Autowired RequirementDefinitionRepository requirementDefinitions;
    @Autowired TenantFixture fixture;

    /**
     * Two transactions satisfy the last two requirements of one milestone at the same
     * moment. Without a row lock on the case, both can read a not-yet-complete
     * milestone, both compute a status/percent from that stale read, and whichever
     * commits last silently overwrites the other's correct, fully-settled
     * conclusion -- a lost update. Idempotency does not save this: each call is
     * individually correct on the state it read. Only serialisation (the row lock)
     * makes the outcome deterministic.
     */
    @Test
    void twoConcurrentSatisfactionsProduceExactlyOneSettledOutcome() throws Exception {
        UUID tenant = fixture.createTenant("rec-race");
        var ids = new AtomicReference<TwoRequirements>();
        fixture.runAs(tenant, () -> ids.set(caseWithOneMilestoneAndTwoRequirements(tenant)));

        var barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> satisfyAfterBarrier(barrier, tenant, ids.get().caseId(), ids.get().first()));
            var b = pool.submit(() -> satisfyAfterBarrier(barrier, tenant, ids.get().caseId(), ids.get().second()));
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        fixture.runAs(tenant, () -> {
            Case c = caseRepository.findById(ids.get().caseId()).orElseThrow();
            assertThat(c.getProgressPercent()).isEqualTo(100);
            Milestone m = milestones.findByCaseIdOrderById(ids.get().caseId()).get(0);
            assertThat(m.getStatus()).isEqualTo(MilestoneStatus.DONE);
            assertThat(m.getCompletedAt()).isNotNull();
        });
    }

    private Void satisfyAfterBarrier(CyclicBarrier barrier, UUID tenant, UUID caseId, UUID requirementId) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        fixture.runAs(tenant, () -> {
            Requirement r = requirements.findById(requirementId).orElseThrow();
            r.setStatus(RequirementStatus.SATISFIED);
            r.setSatisfiedAt(Instant.now());
            requirements.save(r);

            Case c = engine.lockAndLoad(caseId);
            engine.reconcile(c);
        });
        return null;
    }

    private record TwoRequirements(UUID caseId, UUID first, UUID second) {}

    /** One case, one stage, one milestone, two mandatory weight-1 requirements. */
    private TwoRequirements caseWithOneMilestoneAndTwoRequirements(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Race Co " + Uuid7.generate(), null, null, null);

        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenant);
        t.setName("Race Template " + Uuid7.generate());
        t.setStatus(TemplateStatus.ACTIVE);
        templates.save(t);

        WorkflowVersion v = new WorkflowVersion();
        v.setId(Uuid7.generate());
        v.setTenantId(tenant);
        v.setTemplateId(t.getId());
        v.setVersionNo(1);
        v.setStatus(VersionStatus.DRAFT);
        versions.save(v);

        Case c = new Case();
        c.setId(Uuid7.generate());
        c.setTenantId(tenant);
        c.setCustomerId(customerId);
        c.setTemplateId(t.getId());
        c.setVersionId(v.getId());
        c.setStatus(CaseStatus.ACTIVE);
        c.setStartedAt(Instant.now());
        caseRepository.saveAndFlush(c);

        Stage stage = new Stage();
        stage.setId(Uuid7.generate());
        stage.setTenantId(tenant);
        stage.setVersionId(v.getId());
        stage.setOrdinal(1);
        stage.setName("Stage 1");
        stages.saveAndFlush(stage);

        MilestoneDefinition def = new MilestoneDefinition();
        def.setId(Uuid7.generate());
        def.setTenantId(tenant);
        def.setVersionId(v.getId());
        def.setStageId(stage.getId());
        def.setOrdinal(1);
        def.setName("Only milestone");
        def.setEstimatedDurationDays(1);
        milestoneDefinitions.saveAndFlush(def);

        Milestone m = new Milestone();
        m.setId(Uuid7.generate());
        m.setTenantId(tenant);
        m.setCaseId(c.getId());
        m.setMilestoneDefinitionId(def.getId());
        m.setStatus(MilestoneStatus.PENDING);
        milestones.saveAndFlush(m);

        UUID first = newMandatoryRequirement(tenant, c.getId(), v.getId(), def.getId(), m.getId(), 1);
        UUID second = newMandatoryRequirement(tenant, c.getId(), v.getId(), def.getId(), m.getId(), 2);
        return new TwoRequirements(c.getId(), first, second);
    }

    private UUID newMandatoryRequirement(UUID tenant, UUID caseId, UUID versionId, UUID milestoneDefinitionId,
                                          UUID milestoneId, int ordinal) {
        RequirementDefinition rd = new RequirementDefinition();
        rd.setId(Uuid7.generate());
        rd.setTenantId(tenant);
        rd.setVersionId(versionId);
        rd.setMilestoneDefinitionId(milestoneDefinitionId);
        rd.setOrdinal(ordinal);
        rd.setKind(RequirementKind.MANUAL);
        rd.setLabel("Requirement " + ordinal);
        rd.setMandatory(true);
        rd.setWeight(1);
        requirementDefinitions.saveAndFlush(rd);

        Requirement r = new Requirement();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setCaseId(caseId);
        r.setMilestoneId(milestoneId);
        r.setRequirementDefinitionId(rd.getId());
        r.setStatus(RequirementStatus.OPEN);
        return requirements.saveAndFlush(r).getId();
    }
}
