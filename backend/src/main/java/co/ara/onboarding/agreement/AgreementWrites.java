package co.ara.onboarding.agreement;

import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.journey.Case;
import co.ara.onboarding.journey.CaseRepository;
import co.ara.onboarding.journey.Milestone;
import co.ara.onboarding.journey.MilestoneRepository;
import co.ara.onboarding.journey.Requirement;
import co.ara.onboarding.journey.RequirementRepository;
import co.ara.onboarding.journey.StageWriteScopeGuard;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

/**
 * The shared prologue of every agreement write (AgreementService, AgreementReviewService,
 * AgreementSignatureService): resolve through AuthorizedQuery, check lock_version, require
 * a status, apply the stage write scope. Injects repositories, so AuthorizationCoverageTest's
 * finder rule covers it -- it reads only through AuthorizedQuery.
 *
 * <p>{@link #loadForWrite}'s stale-lock branch throws {@code
 * ObjectOptimisticLockingFailureException} directly rather than saving and letting Hibernate's
 * own {@code @Version} check discover the mismatch -- the comparison happens before any write
 * is attempted, so a stale caller never risks partially applying a patch before the conflict
 * is caught. {@code workflow.WorkflowExceptionHandler}'s {@code @RestControllerAdvice} (no
 * {@code basePackages}/{@code assignableTypes} restriction) already maps {@code
 * OptimisticLockingFailureException} -- {@code ObjectOptimisticLockingFailureException}'s own
 * supertype -- to 409 application-wide, so this needs no exception mapping of its own.
 *
 * <p>{@link Milestone} carries no {@code stageId} column of its own -- its stage is reached
 * through its {@code milestoneDefinitionId}, the identical two-hop {@code stageOf} shape
 * {@code journey.RequirementService}/{@code journey.MilestoneService} and {@code
 * task.TaskService}/{@code task.ChecklistService} already establish, not a single {@code
 * m.getStageId()} getter.
 */
@Component
class AgreementWrites {

    private final AgreementRepository agreements;
    private final CaseRepository cases;
    private final RequirementRepository requirements;
    private final MilestoneRepository milestones;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final StageRepository stages;
    private final AuthorizedQuery authorizedQuery;
    private final StageWriteScopeGuard writeScope;

    AgreementWrites(AgreementRepository agreements, CaseRepository cases, RequirementRepository requirements,
                    MilestoneRepository milestones, MilestoneDefinitionRepository milestoneDefinitions,
                    StageRepository stages, AuthorizedQuery authorizedQuery, StageWriteScopeGuard writeScope) {
        this.agreements = agreements;
        this.cases = cases;
        this.requirements = requirements;
        this.milestones = milestones;
        this.milestoneDefinitions = milestoneDefinitions;
        this.stages = stages;
        this.authorizedQuery = authorizedQuery;
        this.writeScope = writeScope;
    }

    Agreement loadForWrite(UUID id, String permission, long lockVersion, Set<AgreementStatus> allowed) {
        Agreement a = authorizedQuery.getById(agreements, Agreement.class, permission, id);
        if (a.getLockVersion() != lockVersion) {
            throw new ObjectOptimisticLockingFailureException(Agreement.class, id);
        }
        if (!allowed.contains(a.getStatus())) {
            throw new IllegalStateException("Agreement " + id + " is " + a.getStatus() + "; this action needs " + allowed);
        }
        applyWriteScope(a, permission);
        return a;
    }

    /** The SIGNATURE requirement's own stage governs who may write its agreement -- not the case's current stage. */
    private void applyWriteScope(Agreement a, String permission) {
        Case c = authorizedQuery.getById(cases, Case.class, permission, a.getCaseId());
        Requirement r = authorizedQuery.getById(requirements, Requirement.class, permission, a.getRequirementId());
        Milestone m = authorizedQuery.getById(milestones, Milestone.class, permission, r.getMilestoneId());
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());
        Stage stage = authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, definition.getStageId());
        writeScope.check(c, m, stage);
    }
}
