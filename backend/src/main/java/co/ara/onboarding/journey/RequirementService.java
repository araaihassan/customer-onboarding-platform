package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.RequirementDefinition;
import co.ara.onboarding.workflow.RequirementDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Satisfies and waives requirements. Every record here is resolved under the
 * WRITE permission (milestone.complete / requirement.waive), never a read one --
 * fetching under a read permission and then writing is the escalation
 * CustomerService.update's own comment already names. write_scope then narrows
 * further, in StageWriteScopeGuard, which has no branch that widens.
 *
 * The milestone a requirement belongs to is resolved through AuthorizedQuery under
 * that same write permission (MilestoneDescriptor, keyed by entity type rather
 * than by the permission's own name -- see AuthorizationPredicateBuilder), never a
 * raw repository finder: AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly
 * already covers co.ara.onboarding.journey.
 */
@Service
public class RequirementService {

    private final RequirementRepository requirements;
    private final MilestoneRepository milestones;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final RequirementDefinitionRepository requirementDefinitions;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final CaseEngine engine;
    private final StageWriteScopeGuard writeScope;
    private final Clock clock;

    public RequirementService(RequirementRepository requirements, MilestoneRepository milestones,
                              MilestoneDefinitionRepository milestoneDefinitions,
                              RequirementDefinitionRepository requirementDefinitions,
                              StageRepository stages, AuthorizedQuery authorizedQuery,
                              AuthContextProvider contextProvider, AuditRecorder audit,
                              CaseEngine engine, StageWriteScopeGuard writeScope, Clock clock) {
        this.requirements = requirements;
        this.milestones = milestones;
        this.milestoneDefinitions = milestoneDefinitions;
        this.requirementDefinitions = requirementDefinitions;
        this.stages = stages;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.engine = engine;
        this.writeScope = writeScope;
        this.clock = clock;
    }

    /**
     * ref/refType are the seam sub-projects 3-5 fill: a task, document or agreement
     * id recorded with no foreign key and a type discriminator, before those
     * tables exist. Idempotent -- a second call on an already-SATISFIED
     * requirement returns the current view without writing or auditing again.
     */
    @RequirePermission(PermissionKeys.MILESTONE_COMPLETE)
    @Transactional
    public CaseRequirementView satisfy(UUID requirementId, UUID ref, String refType) {
        Requirement r = authorizedQuery.getById(
                requirements, Requirement.class, PermissionKeys.MILESTONE_COMPLETE, requirementId);

        Case c = engine.lockAndLoad(r.getCaseId());          // lock BEFORE the write
        if (c.getStatus() == CaseStatus.ON_HOLD) throw new CaseOnHoldException(c.getId());

        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.MILESTONE_COMPLETE, r.getMilestoneId());
        writeScope.check(c, m, stageOf(m));

        if (r.getStatus() == RequirementStatus.SATISFIED) return toView(r);   // idempotent

        r.setStatus(RequirementStatus.SATISFIED);
        r.setSatisfiedAt(Instant.now(clock));
        r.setSatisfiedBy(contextProvider.principal().userId());
        r.setSatisfiedRef(ref);
        r.setSatisfiedRefType(refType);
        requirements.save(r);

        // Cause before effects: reconcile may complete this requirement's
        // milestone (and the case), and those events must not precede the
        // satisfaction that caused them. See AuditRecorder.
        audit.record(AuditActions.REQUIREMENT_SATISFIED, "onboarding_case", c.getId(),
                "Completed " + labelOf(r),
                Map.of("requirementId", r.getId().toString(), "milestoneId", m.getId().toString()));

        engine.reconcile(c);
        return toView(r);
    }

    /** Waiving requires a non-blank reason -- there is no way to waive silently. */
    @RequirePermission(PermissionKeys.REQUIREMENT_WAIVE)
    @Transactional
    public CaseRequirementView waive(UUID requirementId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A waiver reason is required");
        }

        Requirement r = authorizedQuery.getById(
                requirements, Requirement.class, PermissionKeys.REQUIREMENT_WAIVE, requirementId);

        Case c = engine.lockAndLoad(r.getCaseId());
        if (c.getStatus() == CaseStatus.ON_HOLD) throw new CaseOnHoldException(c.getId());

        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.REQUIREMENT_WAIVE, r.getMilestoneId());
        writeScope.check(c, m, stageOf(m));

        if (r.getStatus() == RequirementStatus.WAIVED) return toView(r);   // idempotent

        r.setStatus(RequirementStatus.WAIVED);
        r.setSatisfiedAt(Instant.now(clock));
        r.setSatisfiedBy(contextProvider.principal().userId());
        r.setWaiverReason(reason);
        requirements.save(r);

        audit.record(AuditActions.REQUIREMENT_WAIVED, "onboarding_case", c.getId(),   // cause before effects
                "Waived " + labelOf(r) + ": " + reason,
                Map.of("requirementId", r.getId().toString(), "milestoneId", m.getId().toString()));

        engine.reconcile(c);
        return toView(r);
    }

    /**
     * The mirror of {@link #satisfy}, for when the record that satisfied a
     * requirement stops being trustworthy -- today's only caller is
     * {@code document.DocumentService.retire} (design spec 5.5), reopening
     * whatever a retired document had satisfied. This is the mirror image of
     * sub-project 3's task-cancellation rule, not a contradiction of it: a
     * task is cancelled BEFORE it satisfies, so cancellation is prevented
     * from satisfying at all, but a document is realistically retired AFTER
     * it satisfied -- the wrong file was uploaded -- so retirement must undo
     * a satisfaction that already happened. Leaving the requirement green
     * behind a retired document would be a silent false positive.
     *
     * Gated the SAME permission {@link #satisfy} itself carries
     * ({@code MILESTONE_COMPLETE}), deliberately not a new one -- it composes
     * with {@code document.manage} rather than bypassing it, exactly the
     * precedent {@code task.TaskService.changeStatus}'s own javadoc already
     * states for completing a requirement-linked task requiring BOTH
     * {@code task.complete} and {@code milestone.complete}. A
     * {@code document.manage} holder retiring a document that satisfied a
     * requirement must, by the same logic, also hold {@code milestone.complete}
     * to actually reopen it -- there is no bypass and no new padding
     * permission invented for this.
     *
     * Processes every {@link Requirement} row {@link RequirementRepository#satisfiedBy}
     * finds for ref/refType (there can in principle be more than one, though
     * today's only caller ever supplies a single document's id), skipping any
     * that is not currently SATISFIED (idempotent, the same shape
     * {@link #satisfy}/{@link #waive} already use) -- and, like both of
     * those, never mutates the row the raw finder returned directly: each
     * match is re-resolved through {@link AuthorizedQuery} under
     * {@code MILESTONE_COMPLETE} first, so DEPARTMENT/TEAM/ASSIGNED scope
     * still narrows exactly as it does for a direct call to {@link #satisfy}.
     */
    @RequirePermission(PermissionKeys.MILESTONE_COMPLETE)
    @Transactional
    public void reopen(UUID ref, String refType) {
        for (Requirement candidate : requirements.satisfiedBy(ref, refType)) {
            Requirement r = authorizedQuery.getById(
                    requirements, Requirement.class, PermissionKeys.MILESTONE_COMPLETE, candidate.getId());
            if (r.getStatus() != RequirementStatus.SATISFIED) continue;   // idempotent

            Case c = engine.lockAndLoad(r.getCaseId());          // lock BEFORE the write
            if (c.getStatus() == CaseStatus.ON_HOLD) throw new CaseOnHoldException(c.getId());

            Milestone m = authorizedQuery.getById(
                    milestones, Milestone.class, PermissionKeys.MILESTONE_COMPLETE, r.getMilestoneId());
            writeScope.check(c, m, stageOf(m));

            r.setStatus(RequirementStatus.OPEN);
            r.setSatisfiedAt(null);
            r.setSatisfiedBy(null);
            r.setSatisfiedRef(null);
            r.setSatisfiedRefType(null);
            requirements.save(r);

            // Cause before effects, same as satisfy/waive: reconcile may move
            // this requirement's milestone (and the case) out of DONE, and
            // those events must not precede the reopening that caused them.
            audit.record(AuditActions.REQUIREMENT_REOPENED, "onboarding_case", c.getId(),
                    "Reopened " + labelOf(r),
                    Map.of("requirementId", r.getId().toString(), "milestoneId", m.getId().toString()));

            engine.reconcile(c);
        }
    }

    /** The Stage a milestone belongs to, via its definition -- both ALL-only WORKFLOW_VIEW reads. */
    private Stage stageOf(Milestone m) {
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());
        return authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, definition.getStageId());
    }

    private String labelOf(Requirement r) {
        RequirementDefinition d = authorizedQuery.getById(requirementDefinitions,
                RequirementDefinition.class, PermissionKeys.WORKFLOW_VIEW, r.getRequirementDefinitionId());
        return d.getLabel();
    }

    private CaseRequirementView toView(Requirement r) {
        return new CaseRequirementView(r.getId(), r.getStatus(), r.getSatisfiedAt(), r.getSatisfiedBy(),
                r.getSatisfiedRef(), r.getSatisfiedRefType(), r.getWaiverReason());
    }
}
