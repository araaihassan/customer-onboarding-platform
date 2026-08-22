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

        engine.reconcile(c);
        audit.record(AuditActions.REQUIREMENT_SATISFIED, "onboarding_case", c.getId(),
                "Completed " + labelOf(r),
                Map.of("requirementId", r.getId().toString(), "milestoneId", m.getId().toString()));
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

        engine.reconcile(c);
        audit.record(AuditActions.REQUIREMENT_WAIVED, "onboarding_case", c.getId(),
                "Waived " + labelOf(r) + ": " + reason,
                Map.of("requirementId", r.getId().toString(), "milestoneId", m.getId().toString()));
        return toView(r);
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
