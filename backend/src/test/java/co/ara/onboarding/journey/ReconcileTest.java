package co.ara.onboarding.journey;

import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.support.PostgresTestBase;
import co.ara.onboarding.support.TenantFixture;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.MilestoneDependency;
import co.ara.onboarding.workflow.MilestoneDependencyRepository;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CaseEngine is package-private, so this test calls it directly rather than
 * through CaseService/RoadmapView, which Task 13 has not built yet -- Task 14 is
 * executed first because reconcile is what CaseService.create() will call. See the
 * amendment note above Task 13 and Task 14 in the plan.
 */
class ReconcileTest extends PostgresTestBase {

    @Autowired CaseEngine engine;
    @Autowired CaseRepository caseRepository;
    @Autowired MilestoneRepository milestones;
    @Autowired RequirementRepository requirements;
    @Autowired MilestoneDependencyRepository dependencyRepository;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired WorkflowVersionRepository versions;
    @Autowired StageRepository stages;
    @Autowired MilestoneDefinitionRepository milestoneDefinitions;
    @Autowired RequirementDefinitionRepository requirementDefinitions;
    @Autowired TenantFixture fixture;

    /**
     * Q6's weighting, against hand-computed numbers rather than an assertion that
     * recomputes the formula it is testing. Three milestones of 2, 3 and 5 days in one
     * stage: completing them in order gives 20%, 50%, 100%.
     */
    @Test
    void caseProgressIsWeightedByEstimatedDuration() {
        UUID tenant = fixture.createTenant("rec-weight");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(tenant, 2, 3, 5);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 1));
            assertThat(caseRepository.findById(caseId).orElseThrow().getProgressPercent()).isEqualTo(20);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 2));
            assertThat(caseRepository.findById(caseId).orElseThrow().getProgressPercent()).isEqualTo(50);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 3));
            assertThat(caseRepository.findById(caseId).orElseThrow().getProgressPercent()).isEqualTo(100);
        });
    }

    /**
     * A milestone's own percent is its satisfied requirement WEIGHT, not its count -- a
     * weight of 3 next to two weights of 1 is 60% when only it is done.
     */
    @Test
    void milestoneProgressIsWeightedByRequirementWeight() {
        UUID tenant = fixture.createTenant("rec-req-weight");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithBareMilestone(tenant, 1);
            Milestone m = milestoneOrdinal(caseId, 1);
            UUID versionId = caseRepository.findById(caseId).orElseThrow().getVersionId();
            UUID r1 = newRequirementInstance(tenant, caseId, versionId, m, 1, RequirementKind.MANUAL, true, 3);
            UUID r2 = newRequirementInstance(tenant, caseId, versionId, m, 2, RequirementKind.MANUAL, true, 1);
            UUID r3 = newRequirementInstance(tenant, caseId, versionId, m, 3, RequirementKind.MANUAL, true, 1);

            satisfyRequirement(r1);
            assertThat(reload(m).getProgressPercent()).isEqualTo(60);

            satisfyRequirement(r2);
            assertThat(reload(m).getProgressPercent()).isEqualTo(80);

            satisfyRequirement(r3);
            assertThat(reload(m).getProgressPercent()).isEqualTo(100);
        });
    }

    /** Only MANDATORY requirements gate completion; optional ones still move the bar. */
    @Test
    void anOptionalRequirementDoesNotBlockCompletionButDoesCount() {
        UUID tenant = fixture.createTenant("rec-optional");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithBareMilestone(tenant, 1);
            Milestone m = milestoneOrdinal(caseId, 1);
            UUID versionId = caseRepository.findById(caseId).orElseThrow().getVersionId();
            UUID mandatory = newRequirementInstance(tenant, caseId, versionId, m, 1, RequirementKind.MANUAL, true, 1);
            newRequirementInstance(tenant, caseId, versionId, m, 2, RequirementKind.MANUAL, false, 1);

            satisfyRequirement(mandatory);

            Milestone reloaded = reload(m);
            assertThat(reloaded.getStatus()).isEqualTo(MilestoneStatus.DONE);
            // The optional requirement is still open, so it still counts: 1 of 2 weight settled.
            assertThat(reloaded.getProgressPercent()).isEqualTo(50);
        });
    }

    /** A waived requirement completes a milestone the same way a satisfied one does. */
    @Test
    void aWaivedRequirementCountsAsSettled() {
        UUID tenant = fixture.createTenant("rec-waived");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(tenant, 1);
            Milestone m = milestoneOrdinal(caseId, 1);
            Requirement r = requirements.findByMilestoneId(m.getId()).get(0);
            r.setStatus(RequirementStatus.WAIVED);
            r.setWaiverReason("Not applicable");
            requirements.save(r);
            reconcile(caseId);

            Milestone reloaded = reload(m);
            assertThat(reloaded.getStatus()).isEqualTo(MilestoneStatus.DONE);
            assertThat(reloaded.getProgressPercent()).isEqualTo(100);
        });
    }

    /** Dependencies gate ACTIVE and nothing else. */
    @Test
    void aMilestoneWithAnUnmetDependencyIsBlocked() {
        UUID tenant = fixture.createTenant("rec-blocked");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithDependency(tenant);
            assertThat(milestoneOrdinal(caseId, 2).getStatus()).isEqualTo(MilestoneStatus.BLOCKED);
        });
    }

    /**
     * The invariant locked during design: a blocked milestone goes overdue rather than
     * having its schedule quietly reflowed, because the prototype draws "blocked by X" in
     * red beside a red due date and a plan that hides the blockage is worse than a late
     * one.
     */
    @Test
    void blockingDoesNotMoveADueDate() {
        UUID tenant = fixture.createTenant("rec-dates");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithDependency(tenant);
            LocalDate before = milestoneOrdinal(caseId, 2).getDueDate();
            engineReconcileTwice(caseId);
            assertThat(milestoneOrdinal(caseId, 2).getDueDate()).isEqualTo(before);
        });
    }

    /**
     * Idempotency. Sub-projects 3-5 will each call reconcile after their own writes, and a
     * second call arriving from a retry must be indistinguishable from one call.
     */
    @Test
    void reconcileTwiceChangesNothing() {
        UUID tenant = fixture.createTenant("rec-idem");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(tenant, 2, 3);
            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 1));

            var first = snapshot(caseId);
            engineReconcileTwice(caseId);
            assertThat(snapshot(caseId)).isEqualTo(first);
        });
    }

    /** SKIPPED milestones leave the calculation entirely, or 100% is unreachable. */
    @Test
    void skippedMilestonesAreExcludedFromBothHalvesOfTheFraction() {
        UUID tenant = fixture.createTenant("rec-skip");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(tenant, 2, 3);
            Milestone skipped = milestoneOrdinal(caseId, 1);
            skipped.setStatus(MilestoneStatus.SKIPPED);
            milestones.save(skipped);

            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 2));

            assertThat(caseRepository.findById(caseId).orElseThrow().getProgressPercent()).isEqualTo(100);
            assertThat(reload(skipped).getStatus()).isEqualTo(MilestoneStatus.SKIPPED);
        });
    }

    /** A case whose every milestone is DONE or SKIPPED reads 100, never 99 from rounding. */
    @Test
    void aFullyDoneCaseReadsExactlyOneHundred() {
        UUID tenant = fixture.createTenant("rec-100");
        fixture.runAs(tenant, () -> {
            UUID caseId = caseWithMilestoneDurations(tenant, 1, 1, 1);
            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 1));
            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 2));
            satisfyEveryRequirementOf(milestoneOrdinal(caseId, 3));

            assertThat(caseRepository.findById(caseId).orElseThrow().getProgressPercent()).isEqualTo(100);
        });
    }

    // ---- fixtures ----------------------------------------------------------

    /**
     * A case pinned to a fresh version, with one stage holding one milestone per
     * duration given (ordinals 1..N), each with a single mandatory weight-1
     * requirement. Must be called inside {@link TenantFixture#runAs}.
     */
    private UUID caseWithMilestoneDurations(UUID tenant, int... durations) {
        Case c = newCase(tenant);
        UUID stageId = newStage(tenant, c.getVersionId(), 1, "Stage 1");
        int ordinal = 1;
        for (int duration : durations) {
            MilestoneDefinition def = newMilestoneDefinition(tenant, c.getVersionId(), stageId, ordinal, duration);
            Milestone m = new Milestone();
            m.setId(Uuid7.generate());
            m.setTenantId(tenant);
            m.setCaseId(c.getId());
            m.setMilestoneDefinitionId(def.getId());
            m.setStatus(MilestoneStatus.PENDING);
            m = milestones.saveAndFlush(m);

            RequirementDefinition rd = newRequirementDefinition(tenant, c.getVersionId(), def.getId(), 1, true, 1);
            Requirement r = new Requirement();
            r.setId(Uuid7.generate());
            r.setTenantId(tenant);
            r.setCaseId(c.getId());
            r.setMilestoneId(m.getId());
            r.setRequirementDefinitionId(rd.getId());
            r.setStatus(RequirementStatus.OPEN);
            requirements.saveAndFlush(r);

            ordinal++;
        }
        return c.getId();
    }

    /** One milestone, no requirements at all -- the test adds exactly the ones it needs. */
    private UUID caseWithBareMilestone(UUID tenant, int durationDays) {
        Case c = newCase(tenant);
        UUID stageId = newStage(tenant, c.getVersionId(), 1, "Stage 1");
        MilestoneDefinition def = newMilestoneDefinition(tenant, c.getVersionId(), stageId, 1, durationDays);
        Milestone m = new Milestone();
        m.setId(Uuid7.generate());
        m.setTenantId(tenant);
        m.setCaseId(c.getId());
        m.setMilestoneDefinitionId(def.getId());
        m.setStatus(MilestoneStatus.PENDING);
        milestones.saveAndFlush(m);
        return c.getId();
    }

    /** Milestone 2 depends on milestone 1, which is never satisfied, so 2 stays BLOCKED. */
    private UUID caseWithDependency(UUID tenant) {
        UUID caseId = caseWithMilestoneDurations(tenant, 1, 1);
        Case c = caseRepository.findById(caseId).orElseThrow();
        Milestone m1 = milestoneOrdinal(caseId, 1);
        Milestone m2 = milestoneOrdinal(caseId, 2);

        MilestoneDependency dep = new MilestoneDependency();
        dep.setId(Uuid7.generate());
        dep.setTenantId(tenant);
        dep.setVersionId(c.getVersionId());
        dep.setMilestoneDefinitionId(m2.getMilestoneDefinitionId());
        dep.setDependsOnMilestoneDefinitionId(m1.getMilestoneDefinitionId());
        dependencyRepository.saveAndFlush(dep);

        engine.reconcile(c);
        return caseId;
    }

    private Milestone milestoneOrdinal(UUID caseId, int ordinal) {
        Case c = caseRepository.findById(caseId).orElseThrow();
        Map<UUID, MilestoneDefinition> defs = milestoneDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId())
                .stream().collect(toMap(MilestoneDefinition::getId, d -> d));
        return milestones.findByCaseIdOrderById(caseId).stream()
                .filter(m -> defs.get(m.getMilestoneDefinitionId()).getOrdinal() == ordinal)
                .findFirst().orElseThrow();
    }

    private Milestone reload(Milestone m) {
        return milestones.findById(m.getId()).orElseThrow();
    }

    private void satisfyEveryRequirementOf(Milestone m) {
        for (Requirement r : requirements.findByMilestoneId(m.getId())) {
            r.setStatus(RequirementStatus.SATISFIED);
            r.setSatisfiedAt(Instant.now());
            requirements.save(r);
        }
        reconcile(m.getCaseId());
    }

    private void satisfyRequirement(UUID requirementId) {
        Requirement r = requirements.findById(requirementId).orElseThrow();
        r.setStatus(RequirementStatus.SATISFIED);
        r.setSatisfiedAt(Instant.now());
        requirements.save(r);
        reconcile(r.getCaseId());
    }

    private void reconcile(UUID caseId) {
        Case c = caseRepository.findById(caseId).orElseThrow();
        engine.reconcile(c);
    }

    private void engineReconcileTwice(UUID caseId) {
        Case c = caseRepository.findById(caseId).orElseThrow();
        engine.reconcile(c);
        engine.reconcile(c);
    }

    private Snapshot snapshot(UUID caseId) {
        Case c = caseRepository.findById(caseId).orElseThrow();
        List<MilestoneSnap> snaps = milestones.findByCaseIdOrderById(caseId).stream()
                .map(m -> new MilestoneSnap(m.getId(), m.getStatus(), m.getProgressPercent(),
                        m.getCompletedAt(), m.getDueDate()))
                .toList();
        return new Snapshot(c.getProgressPercent(), c.getTargetCompletionDate(), snaps);
    }

    private record MilestoneSnap(UUID id, MilestoneStatus status, int progressPercent,
                                  Instant completedAt, LocalDate dueDate) {}

    private record Snapshot(int casePercent, LocalDate targetCompletionDate, List<MilestoneSnap> milestoneSnaps) {}

    private UUID newRequirementInstance(UUID tenant, UUID caseId, UUID versionId, Milestone m, int ordinal,
                                         RequirementKind kind, boolean mandatory, int weight) {
        RequirementDefinition rd = new RequirementDefinition();
        rd.setId(Uuid7.generate());
        rd.setTenantId(tenant);
        rd.setVersionId(versionId);
        rd.setMilestoneDefinitionId(m.getMilestoneDefinitionId());
        rd.setOrdinal(ordinal);
        rd.setKind(kind);
        rd.setLabel("Requirement " + ordinal);
        rd.setMandatory(mandatory);
        rd.setWeight(weight);
        requirementDefinitions.saveAndFlush(rd);

        Requirement r = new Requirement();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setCaseId(caseId);
        r.setMilestoneId(m.getId());
        r.setRequirementDefinitionId(rd.getId());
        r.setStatus(RequirementStatus.OPEN);
        return requirements.saveAndFlush(r).getId();
    }

    private Case newCase(UUID tenant) {
        UUID customerId = fixture.createCustomer(tenant, "Acme " + Uuid7.generate(), null, null, null);

        WorkflowTemplate t = new WorkflowTemplate();
        t.setId(Uuid7.generate());
        t.setTenantId(tenant);
        t.setName("Template " + Uuid7.generate());
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
        return caseRepository.saveAndFlush(c);
    }

    private UUID newStage(UUID tenant, UUID versionId, int ordinal, String name) {
        Stage s = new Stage();
        s.setId(Uuid7.generate());
        s.setTenantId(tenant);
        s.setVersionId(versionId);
        s.setOrdinal(ordinal);
        s.setName(name);
        return stages.saveAndFlush(s).getId();
    }

    private MilestoneDefinition newMilestoneDefinition(UUID tenant, UUID versionId, UUID stageId,
                                                         int ordinal, int durationDays) {
        MilestoneDefinition d = new MilestoneDefinition();
        d.setId(Uuid7.generate());
        d.setTenantId(tenant);
        d.setVersionId(versionId);
        d.setStageId(stageId);
        d.setOrdinal(ordinal);
        d.setName("Milestone " + ordinal);
        d.setEstimatedDurationDays(durationDays);
        return milestoneDefinitions.saveAndFlush(d);
    }

    private RequirementDefinition newRequirementDefinition(UUID tenant, UUID versionId, UUID milestoneDefinitionId,
                                                             int ordinal, boolean mandatory, int weight) {
        RequirementDefinition r = new RequirementDefinition();
        r.setId(Uuid7.generate());
        r.setTenantId(tenant);
        r.setVersionId(versionId);
        r.setMilestoneDefinitionId(milestoneDefinitionId);
        r.setOrdinal(ordinal);
        r.setKind(RequirementKind.MANUAL);
        r.setLabel("Requirement " + ordinal);
        r.setMandatory(mandatory);
        r.setWeight(weight);
        return requirementDefinitions.saveAndFlush(r);
    }
}
