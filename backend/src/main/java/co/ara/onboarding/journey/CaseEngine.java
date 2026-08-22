package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.platform.BusinessCalendar;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.AttributeDefinition;
import co.ara.onboarding.workflow.AttributeDefinitionRepository;
import co.ara.onboarding.workflow.BranchRule;
import co.ara.onboarding.workflow.BranchRuleRepository;
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
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;

/**
 * Package-private. @RequirePermission binds to public service methods, so a public
 * engine would be an ungated entry point -- the name-shaped-guard hole CLAUDE.md
 * records for *Directory. AuthorizationCoverageTest additionally binds *Engine from
 * Task 1, so this is guarded twice: unreachable from outside journey, and required to
 * be gated if it ever became reachable.
 *
 * Task 15 fills in stage transitions, branching, the entry-condition skip loop and
 * case completion. Two entry points now exist: {@link #reconcile} mutates and takes
 * the transition; {@link #pendingTransition} is a read-only mirror CaseService.toView
 * calls so a plain {@code get()} (which never calls reconcile) can still show a
 * manual stage's computed-but-not-taken transition. Both share the branch/skip-loop
 * logic through {@link #nextStage}'s {@code mutate} flag, rather than two independent
 * implementations drifting apart.
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
    private final AttributeDefinitionRepository attributeDefinitions;
    private final CaseAttributeValueRepository attributeValues;
    private final BranchRuleRepository branchRules;
    private final ApprovalRepository approvals;
    private final CustomerDirectory customers;
    private final ConditionEvaluator evaluator;
    private final BusinessCalendar calendar;
    private final AuditRecorder audit;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    CaseEngine(CaseRepository cases, StageRepository stageRepository,
               MilestoneDefinitionRepository milestoneDefinitions,
               MilestoneDependencyRepository dependencyRepository,
               MilestoneRepository milestones, RequirementRepository requirements,
               RequirementDefinitionRepository requirementDefinitions,
               AttributeDefinitionRepository attributeDefinitions,
               CaseAttributeValueRepository attributeValues,
               BranchRuleRepository branchRules, ApprovalRepository approvals,
               CustomerDirectory customers, ConditionEvaluator evaluator,
               BusinessCalendar calendar, AuditRecorder audit,
               AuthContextProvider contextProvider, Clock clock) {
        this.cases = cases;
        this.stageRepository = stageRepository;
        this.milestoneDefinitions = milestoneDefinitions;
        this.dependencyRepository = dependencyRepository;
        this.milestones = milestones;
        this.requirements = requirements;
        this.requirementDefinitions = requirementDefinitions;
        this.attributeDefinitions = attributeDefinitions;
        this.attributeValues = attributeValues;
        this.branchRules = branchRules;
        this.approvals = approvals;
        this.customers = customers;
        this.evaluator = evaluator;
        this.calendar = calendar;
        this.audit = audit;
        this.contextProvider = contextProvider;
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
     *
     * Statuses/progress are computed twice per call: once against the state as it was
     * read, and once more after advanceIfExitable, which may skip milestones or enter a
     * new stage. Without the second pass, a case that completes its final stage in this
     * same call would read its OLD progress (not yet 100, because the last stage's
     * milestones were DONE only in this call's in-memory instances, and a newly entered
     * stage's milestones would still read PENDING rather than ACTIVE until a second,
     * unrelated reconcile happened to run. This is an amendment to the plan's literal
     * single-pass pseudocode -- see the Task 15 plan amendment.
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

        recomputeStatusesAndProgress(c, instances, definitions, requirementRows, requirementDefs, dependencies);

        // Stage transitions, branching and completion.
        advanceIfExitable(c, stages, definitions, instances);

        recomputeStatusesAndProgress(c, instances, definitions, requirementRows, requirementDefs, dependencies);
    }

    private void recomputeStatusesAndProgress(Case c, List<Milestone> instances, List<MilestoneDefinition> definitions,
                                               List<Requirement> requirementRows, List<RequirementDefinition> requirementDefs,
                                               List<MilestoneDependency> dependencies) {
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

    private boolean isInCurrentStage(Milestone m, Case c, List<MilestoneDefinition> definitions) {
        if (c.getCurrentStageId() == null) return false;
        return definitions.stream()
                .filter(d -> d.getId().equals(m.getMilestoneDefinitionId()))
                .anyMatch(d -> c.getCurrentStageId().equals(d.getStageId()));
    }

    /**
     * Sets DONE and stamps completedAt only if not already set, so a second reconcile
     * call is indistinguishable from the first -- re-stamping a timestamp on every call
     * is exactly the kind of write that makes "twice changes nothing" fail. The audit
     * record is guarded by the same check, for the same reason: Task 16 catalogues
     * MILESTONE_COMPLETED but reconcile is the only place that ever transitions a
     * milestone to DONE, so this is its one call site.
     */
    private void markDone(Milestone m) {
        boolean firstTime = m.getCompletedAt() == null;
        m.setStatus(MilestoneStatus.DONE);
        if (firstTime) {
            m.setCompletedAt(Instant.now(clock));
            audit.record(AuditActions.MILESTONE_COMPLETED, "milestone", m.getId(),
                    "Completed milestone", Map.of());
        }
    }

    // ---- Task 15: transitions, branching, completion ---------------------------

    /** Called at the end of every reconcile. Computes the transition; takes it only when the stage allows it. */
    private void advanceIfExitable(Case c, List<Stage> stages,
                                    List<MilestoneDefinition> definitions, List<Milestone> instances) {
        if (c.getCurrentStageId() == null) {           // a freshly created case
            Stage first = stages.stream().min(Comparator.comparingInt(Stage::getOrdinal)).orElse(null);
            if (first == null) return;                 // no stages: publish validation should have refused this
            enterStage(c, first, definitions, instances);
            return;
        }
        Stage current = byId(stages, c.getCurrentStageId());
        if (current == null) return;

        if (!isExitable(current, definitions, instances)) return;
        if (current.isRequiresApproval() && !hasApproval(c, current)) {
            ensureStageExitApproval(c, current);       // idempotent: one PENDING row per stage
            return;
        }
        if (!current.isAutoAdvance() && !c.isAdvanceRequested()) return;   // waits for case.advance

        Stage next = nextStage(c, current, stages, definitions, instances, true);
        if (next == null) {
            // The terminal rule. current_stage_id stays on this stage -- a real one, never a
            // skipped one and never null -- and the case completes.
            c.setStatus(CaseStatus.COMPLETED);
            c.setCompletedAt(Instant.now(clock));
            audit.record(AuditActions.CASE_COMPLETED, "onboarding_case", c.getId(),
                    "Case completed", Map.of());
            return;
        }
        enterStage(c, next, definitions, instances);
    }

    /** Every milestone in the stage's definitions must be DONE or SKIPPED for the stage to be exitable. */
    private boolean isExitable(Stage stage, List<MilestoneDefinition> definitions, List<Milestone> instances) {
        for (MilestoneDefinition d : definitionsOf(stage, definitions)) {
            Milestone m = instanceOf(d, instances);
            if (m == null) continue;
            if (m.getStatus() != MilestoneStatus.DONE && m.getStatus() != MilestoneStatus.SKIPPED) return false;
        }
        return true;
    }

    /** An APPROVED STAGE_EXIT approval exists for this stage. Task 17 owns the decide endpoints. */
    private boolean hasApproval(Case c, Stage stage) {
        return approvals.findByCaseIdAndStatus(c.getId(), ApprovalStatus.APPROVED).stream()
                .anyMatch(a -> a.getKind() == ApprovalKind.STAGE_EXIT && stage.getId().equals(a.getStageId()));
    }

    /** Idempotent: only creates a PENDING approval if this case+stage doesn't already have one. */
    private void ensureStageExitApproval(Case c, Stage stage) {
        boolean alreadyPending = approvals.findByCaseIdAndStatus(c.getId(), ApprovalStatus.PENDING).stream()
                .anyMatch(a -> a.getKind() == ApprovalKind.STAGE_EXIT && stage.getId().equals(a.getStageId()));
        if (alreadyPending) return;

        Approval a = new Approval();
        a.setId(Uuid7.generate());
        a.setTenantId(c.getTenantId());
        a.setCaseId(c.getId());
        a.setKind(ApprovalKind.STAGE_EXIT);
        a.setStageId(stage.getId());
        a.setRequestedBy(contextProvider.principal().userId());
        a.setRequestedAt(Instant.now(clock));
        a.setReason("Stage \"" + stage.getName() + "\" is complete and awaits exit approval");
        a.setStatus(ApprovalStatus.PENDING);
        approvals.save(a);
    }

    /**
     * Non-mutating query mirror of the branch/skip walk advanceIfExitable takes, for
     * CaseView's availableTransition -- get()/roadmap() never call reconcile, so this
     * recomputes whether the current stage is exitable-but-manual without persisting
     * anything. Shares branchTargetOf/nextStage with the mutating path via the
     * {@code mutate} flag, so the two can never drift into computing different answers.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    AvailableTransitionView pendingTransition(Case c) {
        if (c.getCurrentStageId() == null) return null;
        if (c.getStatus() == CaseStatus.COMPLETED || c.getStatus() == CaseStatus.CANCELLED) return null;

        var stages = stageRepository.findByVersionIdOrderByOrdinal(c.getVersionId());
        var definitions = milestoneDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId());
        var instances = milestones.findByCaseIdOrderById(c.getId());

        Stage current = byId(stages, c.getCurrentStageId());
        if (current == null || !isExitable(current, definitions, instances)) return null;
        if (current.isRequiresApproval() && !hasApproval(c, current)) {
            return new AvailableTransitionView(null, null, true);
        }
        if (current.isAutoAdvance()) return null;   // takes itself; nothing pending to show

        Stage next = nextStage(c, current, stages, definitions, instances, false);
        if (next == null) return null;              // would complete the case; advance() surfaces that directly
        return new AvailableTransitionView(next.getId(), next.getName(), false);
    }

    /**
     * Branch evaluation, then the entry-condition skip loop.
     *
     * Terminates without a visited set because publish guarantees every branch target has a
     * higher ordinal than its stage, so `cursor` strictly increases. That guarantee is why
     * Task 7's rule 2 is not cosmetic.
     *
     * Amendment to the plan's literal pseudocode: a branch rule or fallback may target a
     * stage more than one ordinal ahead (rule 2 only requires "higher", not "next"), and
     * the plan's walk only called skipMilestonesOf on a stage it evaluated and rejected --
     * never on a stage a jump skipped over entirely without visiting. Left as PENDING
     * forever, those in-between stages' milestones would stay in progressOf's denominator
     * with no way to ever reach DONE, making 100% permanently unreachable for any case that
     * ever took such a branch -- and Q7's own example ("segment=SMB: Legal Review's
     * milestones become SKIPPED") describes exactly this shape. Every stage strictly
     * between the previous cursor and the new one is now skipped on every hop, not only the
     * final rejected one.
     *
     * mutate=false (pendingTransition's read-only path) must never call skipMilestonesOf --
     * a plain get() runs in a read-only transaction, and skipping milestones there would be
     * a write hiding behind a read.
     */
    private Stage nextStage(Case c, Stage from, List<Stage> stages, List<MilestoneDefinition> definitions,
                             List<Milestone> instances, boolean mutate) {
        CustomerFacts customer = customers.findVisible(c.getCustomerId()).orElse(null);
        Map<String, CaseAttributeValue> attributes = attributesByKey(c);

        Stage prev = from;
        Stage cursor = branchTargetOf(prev, customer, attributes, stages);
        while (cursor != null) {
            if (mutate) skipStagesBetween(prev, cursor, stages, c, definitions, instances);
            if (evaluator.matches(cursor.getEntryCondition(), customer, attributes)) return cursor;

            // Skipped, not deleted: the roadmap still draws the stage, greyed, so a reader can
            // see the path the case did not take.
            if (mutate) skipMilestonesOf(cursor, c, definitions, instances);
            prev = cursor;
            cursor = branchTargetOf(prev, customer, attributes, stages);
        }
        return null;
    }

    /** Every stage strictly between two ordinals a single hop jumped over -- see nextStage's amendment note. */
    private void skipStagesBetween(Stage from, Stage to, List<Stage> stages, Case c,
                                    List<MilestoneDefinition> definitions, List<Milestone> instances) {
        for (Stage s : stages) {
            if (s.getOrdinal() > from.getOrdinal() && s.getOrdinal() < to.getOrdinal()) {
                skipMilestonesOf(s, c, definitions, instances);
            }
        }
    }

    /**
     * First match wins, in ordinal order. Ordinal order, not specificity: a rule an admin
     * put first is a rule an admin means to win.
     */
    private Stage branchTargetOf(Stage from, CustomerFacts customer,
                                  Map<String, CaseAttributeValue> attributes, List<Stage> stages) {
        for (BranchRule rule : branchRules.findByStageIdOrderByOrdinal(from.getId())) {
            if (evaluator.matches(rule.getCondition(), customer, attributes)) {
                return byId(stages, rule.getTargetStageId());
            }
        }
        if (from.getFallbackNextStageId() != null) return byId(stages, from.getFallbackNextStageId());
        return stages.stream().filter(s -> s.getOrdinal() == from.getOrdinal() + 1).findFirst().orElse(null);
    }

    private void skipMilestonesOf(Stage stage, Case c, List<MilestoneDefinition> definitions, List<Milestone> instances) {
        for (MilestoneDefinition d : definitionsOf(stage, definitions)) {
            Milestone m = instanceOf(d, instances);
            if (m == null || m.getStatus() == MilestoneStatus.DONE || m.getStatus() == MilestoneStatus.SKIPPED) continue;
            m.setStatus(MilestoneStatus.SKIPPED);
            audit.record(AuditActions.MILESTONE_SKIPPED, "milestone", m.getId(),
                    "Skipped milestone \"" + d.getName() + "\"",
                    Map.of("caseId", c.getId().toString(), "stageId", stage.getId().toString()));
        }
    }

    /**
     * Entering a stage is where due dates are set, in business days, cumulative over prior
     * milestones in this stage by ordinal. Dependencies do not enter into it: they gate
     * ACTIVE, and a plan that reflows around a blockage hides it.
     */
    private void enterStage(Case c, Stage stage, List<MilestoneDefinition> definitions, List<Milestone> instances) {
        c.setCurrentStageId(stage.getId());
        LocalDate cursor = LocalDate.now(clock);
        int cumulative = 0;
        for (MilestoneDefinition definition : definitionsOf(stage, definitions)) {
            cumulative += definition.getEstimatedDurationDays();
            Milestone m = instanceOf(definition, instances);
            if (m != null) m.setDueDate(calendar.plusBusinessDays(cursor, cumulative));
        }
        c.setTargetCompletionDate(latestDueDate(instances));
        audit.record(AuditActions.CASE_STAGE_ENTERED, "onboarding_case", c.getId(),
                "Entered stage \"" + stage.getName() + "\"", Map.of("stageId", stage.getId().toString()));
    }

    /** The furthest-out due date scheduled so far -- only entered stages have one. */
    private LocalDate latestDueDate(List<Milestone> instances) {
        return instances.stream().map(Milestone::getDueDate).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
    }

    private List<MilestoneDefinition> definitionsOf(Stage stage, List<MilestoneDefinition> definitions) {
        return definitions.stream().filter(d -> d.getStageId().equals(stage.getId())).toList();
    }

    private Milestone instanceOf(MilestoneDefinition definition, List<Milestone> instances) {
        return instances.stream().filter(m -> m.getMilestoneDefinitionId().equals(definition.getId()))
                .findFirst().orElse(null);
    }

    private Stage byId(List<Stage> stages, UUID id) {
        return stages.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(null);
    }

    /**
     * The case's attribute answers, keyed by the attribute's declared key string --
     * CaseAttributeValue itself carries only attributeDefinitionId, so the key comes
     * from a join against AttributeDefinition (ALL-only, read directly: see the
     * BranchRuleRepository/CaseAttributeValueRepository javadocs on why CaseEngine is
     * exempt from the finder-call rule).
     */
    private Map<String, CaseAttributeValue> attributesByKey(Case c) {
        Map<UUID, String> keyByDefinitionId = attributeDefinitions.findByVersionIdOrderByOrdinal(c.getVersionId())
                .stream().collect(toMap(AttributeDefinition::getId, AttributeDefinition::getKey));
        return attributeValues.findByCaseId(c.getId()).stream()
                .filter(v -> keyByDefinitionId.containsKey(v.getAttributeDefinitionId()))
                .collect(toMap(v -> keyByDefinitionId.get(v.getAttributeDefinitionId()), v -> v));
    }
}
