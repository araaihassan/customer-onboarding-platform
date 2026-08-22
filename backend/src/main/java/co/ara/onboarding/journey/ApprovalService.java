package co.ara.onboarding.journey;

import co.ara.onboarding.audit.AuditActions;
import co.ara.onboarding.audit.AuditRecorder;
import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decides approvals. Two decide methods, not one, because a single path could not
 * carry approval.decide for a stage exit and milestone.force_approve for a
 * forcing without hiding an authorization decision inside the method body where
 * no coverage test can see it. Both check the approval's kind before anything
 * else -- a mismatch means the caller reached this approval through the other
 * decide path.
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvals;
    private final MilestoneRepository milestones;
    private final CaseRepository cases;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final AuditRecorder audit;
    private final CaseEngine engine;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, MilestoneRepository milestones, CaseRepository cases,
                           AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                           AuditRecorder audit, CaseEngine engine, Clock clock) {
        this.approvals = approvals;
        this.milestones = milestones;
        this.cases = cases;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
        this.audit = audit;
        this.engine = engine;
        this.clock = clock;
    }

    /**
     * Confirms the case itself is visible first, so an out-of-scope caseId is a 404
     * rather than a silently empty list -- CaseService.participants' own reasoning.
     */
    @RequirePermission(PermissionKeys.CASE_VIEW)
    @Transactional(readOnly = true)
    public List<ApprovalView> listForCase(UUID caseId) {
        authorizedQuery.getById(cases, Case.class, PermissionKeys.CASE_VIEW, caseId);

        Specification<Approval> byCase = (root, query, cb) -> cb.equal(root.get("caseId"), caseId);
        return authorizedQuery.findAll(approvals, Approval.class, PermissionKeys.CASE_VIEW, byCase, Pageable.unpaged())
                .getContent().stream().map(MilestoneService::toView).toList();
    }

    /**
     * Approving re-reconciles the case: CaseEngine.hasApproval now sees this
     * APPROVED row (the auto-flush that AuthorizedQuery's / reconcile's own
     * queries trigger sees this method's own pending changes within the same
     * transaction, exactly as RequirementService.satisfy already relies on), so
     * the stage genuinely advances here if it is otherwise exitable and
     * auto_advance. If auto_advance is false, the case still waits for someone
     * holding case.advance -- approval and manual-advance are independent gates.
     */
    @RequirePermission(PermissionKeys.APPROVAL_DECIDE)
    @Transactional
    public ApprovalView decideStageExit(UUID approvalId, boolean approve, String note) {
        Approval a = authorizedQuery.getById(
                approvals, Approval.class, PermissionKeys.APPROVAL_DECIDE, approvalId);

        if (a.getKind() != ApprovalKind.STAGE_EXIT) throw new ApprovalKindMismatchException(approvalId);
        if (a.getStatus() != ApprovalStatus.PENDING) throw new ApprovalAlreadyDecidedException(approvalId);

        UUID decider = contextProvider.principal().userId();
        Case c = engine.lockAndLoad(a.getCaseId());

        a.setStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        a.setDecidedBy(decider);
        a.setDecidedAt(Instant.now(clock));
        a.setDecisionNote(note);
        approvals.save(a);

        if (approve) {
            engine.reconcile(c);
            audit.record(AuditActions.CASE_STAGE_EXIT_APPROVED, "onboarding_case", c.getId(),
                    "Approved stage exit", Map.of("approvalId", a.getId().toString()));
        } else {
            audit.record(AuditActions.CASE_STAGE_EXIT_REJECTED, "onboarding_case", c.getId(),
                    "Rejected stage exit", Map.of("approvalId", a.getId().toString()));
        }
        return MilestoneService.toView(a);
    }

    /**
     * Q5's three requirements: the reason is NOT NULL in the schema (enforced at
     * the request boundary by MilestoneService), the decider must differ from
     * the requester, and an approved forcing writes milestone.force_completed --
     * never milestone.completed -- so it never reads as an ordinary completion.
     */
    @RequirePermission(PermissionKeys.MILESTONE_FORCE_APPROVE)
    @Transactional
    public ApprovalView decideForceComplete(UUID approvalId, boolean approve, String note) {
        Approval a = authorizedQuery.getById(
                approvals, Approval.class, PermissionKeys.MILESTONE_FORCE_APPROVE, approvalId);

        if (a.getKind() != ApprovalKind.FORCE_COMPLETE) throw new ApprovalKindMismatchException(approvalId);
        if (a.getStatus() != ApprovalStatus.PENDING) throw new ApprovalAlreadyDecidedException(approvalId);

        UUID decider = contextProvider.principal().userId();
        // What makes this more than theatre. Administrator holds both
        // MILESTONE_FORCE_COMPLETE and MILESTONE_FORCE_APPROVE, so without this a
        // single person could request and approve in two calls with nobody else involved.
        if (decider.equals(a.getRequestedBy())) throw new SelfApprovalException(approvalId);

        Case c = engine.lockAndLoad(a.getCaseId());

        a.setStatus(approve ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        a.setDecidedBy(decider);
        a.setDecidedAt(Instant.now(clock));
        a.setDecisionNote(note);
        approvals.save(a);

        if (approve) {
            Milestone m = authorizedQuery.getById(
                    milestones, Milestone.class, PermissionKeys.MILESTONE_FORCE_APPROVE, a.getMilestoneId());
            m.setStatus(MilestoneStatus.DONE);
            m.setCompletedAt(Instant.now(clock));
            m.setCompletedBy(decider);
            m.setCompletionReason(a.getReason());      // carried from the request, not the decision
            milestones.save(m);
            engine.reconcile(c);
            audit.record(AuditActions.MILESTONE_FORCE_COMPLETED, "milestone", m.getId(),
                    "Forced completion: " + a.getReason(),
                    Map.of("approvalId", a.getId().toString(), "requestedBy", a.getRequestedBy().toString()));
        } else {
            audit.record(AuditActions.MILESTONE_FORCE_REJECTED, "milestone", a.getMilestoneId(),
                    "Rejected forced completion", Map.of("approvalId", a.getId().toString()));
        }
        return MilestoneService.toView(a);
    }
}
