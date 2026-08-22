package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RelationshipType;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.identity.AppUser;
import co.ara.onboarding.identity.AppUserRepository;
import co.ara.onboarding.platform.Uuid7;
import co.ara.onboarding.workflow.MilestoneDefinition;
import co.ara.onboarding.workflow.MilestoneDefinitionRepository;
import co.ara.onboarding.workflow.Stage;
import co.ara.onboarding.workflow.StageRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Requests a forced completion, reassigns/reschedules milestones (Q15's manual
 * override) and reopens them for rework. Every record here is resolved through
 * AuthorizedQuery under the WRITE permission the calling method is itself gated
 * on, never a raw repository finder -- AuthorizationCoverageTest.
 * servicesDoNotCallRepositoryFindersDirectly covers co.ara.onboarding.journey.
 * Deciding a force-completion request is ApprovalService's job: two different
 * permissions gate the two halves of Q5's flow (request vs. approve), so they
 * cannot live on the same method without hiding which key gates what.
 */
@Service
public class MilestoneService {

    private final MilestoneRepository milestones;
    private final RequirementRepository requirements;
    private final ApprovalRepository approvals;
    private final CaseParticipantRepository participants;
    private final MilestoneDefinitionRepository milestoneDefinitions;
    private final StageRepository stages;
    private final AppUserRepository users;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final CaseEngine engine;
    private final StageWriteScopeGuard writeScope;
    private final Clock clock;

    public MilestoneService(MilestoneRepository milestones, RequirementRepository requirements,
                            ApprovalRepository approvals, CaseParticipantRepository participants,
                            MilestoneDefinitionRepository milestoneDefinitions, StageRepository stages,
                            AppUserRepository users, AuthorizedQuery authorizedQuery,
                            AuthContextProvider contextProvider, AuditRecorder audit, CaseEngine engine,
                            StageWriteScopeGuard writeScope, Clock clock) {
        this.milestones = milestones;
        this.requirements = requirements;
        this.approvals = approvals;
        this.participants = participants;
        this.milestoneDefinitions = milestoneDefinitions;
        this.stages = stages;
        this.users = users;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.engine = engine;
        this.writeScope = writeScope;
        this.clock = clock;
    }

    /**
     * Q5 requirement one: a reason, unavoidable because approval.reason is NOT
     * NULL at the database layer -- this boundary check just gives a clean
     * exception instead of a constraint violation. Requirement two (the decider
     * cannot be the requester) and requirement three (a distinct action key) are
     * ApprovalService.decideForceComplete's job, not this method's.
     */
    @RequirePermission(PermissionKeys.MILESTONE_FORCE_COMPLETE)
    @Transactional
    public ApprovalView requestForceComplete(UUID milestoneId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to request a forced completion");
        }
        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.MILESTONE_FORCE_COMPLETE, milestoneId);

        Approval a = new Approval();
        a.setId(Uuid7.generate());
        a.setTenantId(m.getTenantId());
        a.setCaseId(m.getCaseId());
        a.setKind(ApprovalKind.FORCE_COMPLETE);
        a.setMilestoneId(m.getId());
        a.setRequestedBy(contextProvider.principal().userId());
        a.setRequestedAt(Instant.now(clock));
        a.setReason(reason);
        a.setStatus(ApprovalStatus.PENDING);
        approvals.save(a);
        return toView(a);
    }

    static ApprovalView toView(Approval a) {
        return new ApprovalView(a.getId(), a.getKind(), a.getStageId(), a.getMilestoneId(),
                a.getStatus(), a.getReason(), a.getRequestedBy(), a.getRequestedAt(),
                a.getDecidedBy(), a.getDecidedAt(), a.getDecisionNote());
    }

    /**
     * Q15's manual override: either field may be reassigned/rescheduled
     * independently. The owner id is resolved through AuthorizedQuery under
     * USER_VIEW before it is written -- another tenant's user is a 404, never a
     * cross-tenant row -- and, per Q15's own invariant, a genuinely new owner
     * also becomes an ASSIGNEE participant on the case, or they would hold a
     * milestone inside a case their own ASSIGNED scope cannot open.
     *
     * reconcile is called afterward for status/progress consistency, but it
     * never touches dueDate (Task 14's own invariant), so the hand-set date
     * here -- a decision, not a derived fact -- survives it.
     */
    @RequirePermission(PermissionKeys.MILESTONE_EDIT)
    @Transactional
    public MilestoneView update(UUID milestoneId, UpdateMilestoneRequest request) {
        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.MILESTONE_EDIT, milestoneId);
        Case c = engine.lockAndLoad(m.getCaseId());
        writeScope.check(c, m, stageOf(m));

        UUID ownerUserId = request.ownerUserId() == null ? m.getOwnerUserId()
                : authorizedQuery.getById(users, AppUser.class, PermissionKeys.USER_VIEW, request.ownerUserId())
                        .getId();
        boolean ownerChanged = !Objects.equals(m.getOwnerUserId(), ownerUserId);

        m.setOwnerUserId(ownerUserId);
        if (request.dueDate() != null) {
            m.setDueDate(request.dueDate());
        }
        milestones.save(m);

        // A due-date-only reschedule is not a reassignment: the action key and
        // its audit row exist specifically for a change of hands, the same
        // precision CLAUDE.md calls out for contact.deactivated vs contact.updated.
        if (ownerChanged) {
            ensureAssigneeParticipant(c, ownerUserId);
            audit.record(AuditActions.MILESTONE_REASSIGNED, "milestone", m.getId(),
                    "Reassigned milestone", Map.of("ownerUserId", ownerUserId.toString()));
        }

        engine.reconcile(c);
        return toView(m);
    }

    /**
     * The rework path (Task 7 rejects backward branches, so this is deliberately
     * an explicit action rather than an invisible graph edge). Rewinds the case's
     * currentStageId to the reopened milestone's own stage -- reconcile's
     * isInCurrentStage is the only rule that produces ACTIVE, and it compares
     * against that column, so hand-setting the milestone's status to ACTIVE
     * instead would just be overwritten by the very reconcile call this method
     * makes.
     *
     * DONE is sticky in CaseEngine.reconcile (Task 17, for force-completion), so
     * a milestone left at DONE here would never be recomputed at all -- clearing
     * completedAt alone is not enough to unstick it. Moving it to PENDING first
     * is only ever a transient value: reconcile's own cascade (mandatorySettled /
     * hasUnmetDependency / isInCurrentStage) decides the real status milliseconds
     * later, in the same transaction.
     */
    @RequirePermission(PermissionKeys.MILESTONE_REOPEN)
    @Transactional
    public void reopen(UUID milestoneId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A reason is required to reopen a milestone");
        }
        Milestone m = authorizedQuery.getById(
                milestones, Milestone.class, PermissionKeys.MILESTONE_REOPEN, milestoneId);
        Case c = engine.lockAndLoad(m.getCaseId());
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());

        m.setStatus(MilestoneStatus.PENDING);
        m.setCompletedAt(null);
        m.setCompletedBy(null);
        m.setCompletionReason(null);
        milestones.save(m);

        Specification<Requirement> byMilestone = (root, query, cb) -> cb.equal(root.get("milestoneId"), m.getId());
        List<Requirement> requirementRows = authorizedQuery.findAll(requirements, Requirement.class,
                        PermissionKeys.MILESTONE_REOPEN, byMilestone, Pageable.unpaged())
                .getContent();
        for (Requirement r : requirementRows) {
            r.setStatus(RequirementStatus.OPEN);
            r.setSatisfiedAt(null);
            r.setSatisfiedBy(null);
            r.setSatisfiedRef(null);
            r.setSatisfiedRefType(null);
            r.setWaiverReason(null);
            requirements.save(r);
        }

        c.setCurrentStageId(definition.getStageId());
        if (c.getStatus() == CaseStatus.COMPLETED) {
            // A completed case with outstanding work is a lie every dashboard
            // would repeat.
            c.setStatus(CaseStatus.ACTIVE);
            c.setCompletedAt(null);
        }
        engine.reconcile(c);

        audit.record(AuditActions.MILESTONE_REOPENED, "milestone", m.getId(),
                "Reopened milestone: " + reason, Map.of("caseId", c.getId().toString()));
    }

    /** The Stage a milestone belongs to, via its definition -- both ALL-only WORKFLOW_VIEW reads. */
    private Stage stageOf(Milestone m) {
        MilestoneDefinition definition = authorizedQuery.getById(milestoneDefinitions,
                MilestoneDefinition.class, PermissionKeys.WORKFLOW_VIEW, m.getMilestoneDefinitionId());
        return authorizedQuery.getById(stages, Stage.class, PermissionKeys.WORKFLOW_VIEW, definition.getStageId());
    }

    /** Idempotent: does nothing if the new owner is already an ACTIVE participant. */
    private void ensureAssigneeParticipant(Case c, UUID userId) {
        if (userId == null) return;
        Specification<CaseParticipant> byCaseAndUser = (root, query, cb) -> cb.and(
                cb.equal(root.get("caseId"), c.getId()),
                cb.equal(root.get("userId"), userId),
                cb.equal(root.get("status"), ParticipantStatus.ACTIVE));
        boolean exists = authorizedQuery.findAll(participants, CaseParticipant.class,
                        PermissionKeys.MILESTONE_EDIT, byCaseAndUser, Pageable.unpaged())
                .hasContent();
        if (exists) return;

        CaseParticipant p = new CaseParticipant();
        p.setId(Uuid7.generate());
        p.setTenantId(c.getTenantId());
        p.setCaseId(c.getId());
        p.setUserId(userId);
        p.setRelationship(RelationshipType.ASSIGNEE);
        p.setStatus(ParticipantStatus.ACTIVE);
        participants.save(p);
    }

    private MilestoneView toView(Milestone m) {
        return new MilestoneView(m.getId(), m.getCaseId(), m.getStatus(), m.getOwnerUserId(),
                m.getDueDate(), m.getProgressPercent(), m.getCompletedAt(), m.getCompletedBy(),
                m.getCompletionReason());
    }
}
