package co.ara.onboarding.journey;

import co.ara.onboarding.authz.AuthContextProvider;
import co.ara.onboarding.authz.AuthorizedQuery;
import co.ara.onboarding.authz.PermissionKeys;
import co.ara.onboarding.authz.RequirePermission;
import co.ara.onboarding.platform.Uuid7;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Requests a forced completion. The milestone is resolved under
 * MILESTONE_FORCE_COMPLETE, never a raw repository finder --
 * AuthorizationCoverageTest.servicesDoNotCallRepositoryFindersDirectly covers
 * co.ara.onboarding.journey. Deciding the request is ApprovalService's job: two
 * different permissions gate the two halves of Q5's flow (request vs. approve), so
 * they cannot live on the same method without hiding which key gates what.
 */
@Service
public class MilestoneService {

    private final MilestoneRepository milestones;
    private final ApprovalRepository approvals;
    private final AuthorizedQuery authorizedQuery;
    private final AuthContextProvider contextProvider;
    private final Clock clock;

    public MilestoneService(MilestoneRepository milestones, ApprovalRepository approvals,
                            AuthorizedQuery authorizedQuery, AuthContextProvider contextProvider,
                            Clock clock) {
        this.milestones = milestones;
        this.approvals = approvals;
        this.authorizedQuery = authorizedQuery;
        this.contextProvider = contextProvider;
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
}
