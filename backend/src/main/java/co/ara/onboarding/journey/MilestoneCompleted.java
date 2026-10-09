package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * Published once per milestone completion, after its audit record: from CaseEngine.markDone
 * for a natural completion, from ApprovalService.decideForceComplete for a forced one.
 */
public record MilestoneCompleted(UUID milestoneId, UUID caseId, boolean forced, UUID actorId) {}
