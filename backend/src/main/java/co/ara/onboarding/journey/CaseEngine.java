package co.ara.onboarding.journey;

import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.MilestoneDependency;
import co.ara.onboarding.workflow.MilestoneDependencyRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

/**
 * Package-private. @RequirePermission binds to public service methods, so a public
 * engine would be an ungated entry point -- the name-shaped-guard hole CLAUDE.md
 * records for *Directory. AuthorizationCoverageTest additionally binds *Engine from
 * Task 1, so this is guarded twice: unreachable from outside journey, and required to
 * be gated if it ever became reachable.
 *
 * Task 14 covers milestone status/progress and case progress only. advanceIfExitable
 * is a deliberate no-op stub here -- Task 15 fills in stage transitions, branching and
 * completion, including the first assignment of currentStageId. Until then a case
 * reconciled by this class alone has every eligible milestone sitting PENDING rather
 * than ACTIVE, because isInCurrentStage(...) has no current stage to compare against.
 * That is a known, temporary gap the plan itself calls out ("Task 15 fills this in"),
 * not a defect of this task.
 */
@Component
class CaseEngine {

    private final CaseRepository cases;
    private final StageRepository stageRepository;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final MilestoneDependencyRepository dependencyRepository;
    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final Clock clock;

    CaseEngine(CaseRepository cases, StageRepository stageRepository,
               MilestoneDefinitionRepository milestoneDefinitions,
               MilestoneDependencyRepository dependencyRepository,
               MilestoneRepository milestones, RequirementRepository requirements,
               RequirementDefinitionRepository requirementDefinitions, Clock clock) {
        this.cases = cases;
        this.stageRepository = stageRepository;
        this.milestoneDefinitions = milestoneDefinitions;
        this.dependencyRepository = dependencyRepository;
        this.milestones = milestones;
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.clock = clock;
    }

    /**
     * The single lock-acquisition point. Callers resolve and authorize the case through
     * AuthorizedQuery first; this re-reads the same row FOR UPDATE.
     *
     * Lock BEFORE the mutation, always. Every entry point then acquires locks in one
     * order, so two engine transactions touching overlapping rows serialise instead of
     * deadlocking. Locking after the write would still serialise reconcile, but two
     * transactions could interleave their writes first and each reconcile a state the
     * other had already invalidated.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Case lockAndLoad(UUID caseId) {
        return cases.lockById(caseId)
                .orElseThrow(() -> new NoSuchElementException("Not found"));
    }

    /**
     * Recomputes everything derivable from persisted state. Idempotent: it reads rows and
     * writes conclusions, and never appends to a history or increments a counter.
     *
     * Deliberately NOT an event chain. Each mutation firing the next step double-advances
     * the moment two sub-projects satisfy the last two requirements of a milestone
     * concurrently, and it can only be tested end-to-end.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    void reconcile(Case c) {
        if (c.getStatus() == CaseStatus.COMPLETED || c.getStatus() == CaseStatus.CANCELLED) return;

        var stages = stageRepository.findByVersionIdOrderByOrdinal(c.getVersionId());
        var definitions = milestoneDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId());
        var dependencies = dependencyRepository.findByVersionId(c.getVersionId());
        var instances = milestones.findByCaseIdOrderById(c.getId());
        var requirementRows = requirements.findByCaseId(c.getId());
        var requirementDefs = requirementDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId());

        // 1. Milestone percent, from satisfied requirement WEIGHT.
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;
            m.setProgressPercent(weightedPercent(m, requirementRows, requirementDefs));
        }

        // 2. Statuses. Order matters: a milestone can only be DONE once its requirements
        // are settled, and only BLOCKED once its dependencies are known.
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;
            if (mandatorySettled(m, requirementRows, requirementDefs)) {
                markDone(m);
            } else if (hasUnmetDependency(m, instances, dependencies)) {
                m.setStatus(MilestoneStatus.BLOCKED);
            } else if (isInCurrentStage(m, c, definitions)) {
                m.setStatus(MilestoneStatus.ACTIVE);
            } else {
                m.setStatus(MilestoneStatus.PENDING);
            }
        }

        // 3. Case progress: milestones weighted by estimated_duration_days, with SKIPPED
        // excluded from BOTH halves. Leaving them in the denominator makes 100%
        // unreachable for any case that ever skipped a stage.
        c.setProgressPercent(progressOf(c, instances, definitions));

        // 4. Stage transitions, branching and completion. Task 15 fills this in.
        advanceIfExitable(c, stages, definitions, instances);
    }

    /**
     * Integer arithmetic with an exact-100 guard. Three equal milestones would otherwise
     * read 33 + 33 + 33 = 99 when every one of them is done, and a roadmap that says 99%
     * with nothing left to do is a bug report waiting to be filed.
     */
    int progressOf(Case c, List<Milestone> instances, List<MilestoneDefinition> definitions) {
        Map<UUID, MilestoneDefinition> byId = definitions.stream()
                .collect(toMap(MilestoneDefinition::getId, d -> d));

        int total = 0, done = 0;
        boolean anyOutstanding = false;
        for (Milestone m : instances) {
            if (m.getStatus() == MilestoneStatus.SKIPPED) continue;      // out of both halves
            var definition = byId.get(m.getMilestoneDefinitionId());
            if (definition == null) continue;
            int weight = definition.getEstimatedDurationDays();
            total += weight;
            if (m.getStatus() == MilestoneStatus.DONE) done += weight;
            else anyOutstanding = true;
        }
        if (total == 0) return 0;
        if (!anyOutstanding) return 100;
        return Math.min(99, (int) Math.round(done * 100.0 / total));
    }

    /**
     * A milestone's own percent, weighted by requirement WEIGHT rather than count.
     * Every requirement counts toward the denominator, mandatory or not -- only
     * mandatorySettled() gates completion; this gates the number shown alongside it.
     */
    private int weightedPercent(Milestone m, List<Requirement> requirementRows,
                                 List<RequirementDefinition> requirementDefs) {
        Map<UUID, RequirementDefinition> defsById = requirementDefs.stream()
                .collect(toMap(RequirementDefinition::getId, d -> d));

        int total = 0, settled = 0;
        boolean anyOutstanding = false;
        for (Requirement r : requirementRows) {
            if (!m.getId().equals(r.getMilestoneId())) continue;
            RequirementDefinition def = defsById.get(r.getRequirementDefinitionId());
            if (def == null) continue;
            total += def.getWeight();
            if (r.getStatus() == RequirementStatus.SATISFIED || r.getStatus() == RequirementStatus.WAIVED) {
                settled += def.getWeight();
            } else {
                anyOutstanding = true;
            }
        }
        if (total == 0) return 100;
        if (!anyOutstanding) return 100;
        return Math.min(99, (int) Math.round(settled * 100.0 / total));
    }

    /** Only MANDATORY requirements gate completion; a satisfied OR waived one is settled. */
    private boolean mandatorySettled(Milestone m, List<Requirement> requirementRows,
                                      List<RequirementDefinition> requirementDefs) {
        Map<UUID, RequirementDefinition> defsById = requirementDefs.stream()
                .collect(toMap(RequirementDefinition::getId, d -> d));

        for (Requirement r : requirementRows) {
            if (!m.getId().equals(r.getMilestoneId())) continue;
            RequirementDefinition def = defsById.get(r.getRequirementDefinitionId());
            if (def == null || !def.isMandatory()) continue;
            boolean settled = r.getStatus() == RequirementStatus.SATISFIED
                    || r.getStatus() == RequirementStatus.WAIVED;
            if (!settled) return false;
        }
        return true;
    }

    /**
     * Dependencies gate ACTIVE and nothing else -- a blocked milestone goes overdue
     * rather than having its schedule quietly reflowed. A dependency is met once the
     * depended-on milestone is DONE or SKIPPED; SKIPPED counts because a dependent
     * cannot wait forever on a stage that will never run.
     */
    private boolean hasUnmetDependency(Milestone m, List<Milestone> instances,
                                        List<MilestoneDependency> dependencies) {
        Map<UUID, Milestone> instanceByDefinitionId = instances.stream()
                .collect(toMap(Milestone::getMilestoneDefinitionId, i -> i, (a, b) -> a));

        for (MilestoneDependency d : dependencies) {
            if (!m.getMilestoneDefinitionId().equals(d.getMilestoneDefinitionId())) continue;
            Milestone dependsOn = instanceByDefinitionId.get(d.getDependsOnMilestoneDefinitionId());
            if (dependsOn == null) continue;
            boolean settled = dependsOn.getStatus() == MilestoneStatus.DONE
                    || dependsOn.getStatus() == MilestoneStatus.SKIPPED;
            if (!settled) return true;
        }
        return false;
    }

    /**
     * Task 15 assigns currentStageId as part of advancing; until then this always
     * reads false, which is why every not-yet-done, not-blocked milestone falls to
     * PENDING rather than ACTIVE for the moment.
     */
    private boolean isInCurrentStage(Milestone m, Case c, List<MilestoneDefinition> definitions) {
        if (c.getCurrentStageId() == null) return false;
        return definitions.stream()
                .filter(d -> d.getId().equals(m.getMilestoneDefinitionId()))
                .anyMatch(d -> c.getCurrentStageId().equals(d.getStageId()));
    }

    /**
     * Sets DONE and stamps completedAt only if not already set, so a second reconcile
     * call is indistinguishable from the first -- re-stamping a timestamp on every call
     * is exactly the kind of write that makes "twice changes nothing" fail.
     */
    private void markDone(Milestone m) {
        m.setStatus(MilestoneStatus.DONE);
        if (m.getCompletedAt() == null) {
            m.setCompletedAt(Instant.now(clock));
        }
    }

    /**
     * Stub. Task 15 implements stage entry/exit, first-match branching, the skip loop
     * and the terminal (case-completion) rule here. reconcile above must not touch
     * dueDate or currentStageId until that lands.
     */
    private void advanceIfExitable(Case c, List<Stage> stages, List<MilestoneDefinition> definitions,
                                    List<Milestone> instances) {
        // Intentionally empty for Task 14.
    }
}
