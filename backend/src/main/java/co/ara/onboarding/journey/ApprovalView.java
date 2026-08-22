package co.ara.onboarding.journey;

import java.time.Instant;
import java.util.UUID;

/** What requestForceComplete/decideStageExit/decideForceComplete return. */
public record ApprovalView(UUID id, ApprovalKind kind, UUID stageId, UUID milestoneId,
                            ApprovalStatus status, String reason, UUID requestedBy, Instant requestedAt,
                            UUID decidedBy, Instant decidedAt, String decisionNote) {}
