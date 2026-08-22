package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * Non-null on CaseView when the current stage is exitable but hasn't taken its
 * transition yet: either {@code approvalPending} is true (the stage requires
 * approval and none has been granted), or {@code nextStageId} names the stage
 * auto_advance=false is holding for someone with case.advance. The builder's
 * "Advance" button binds to this rather than recomputing exitability client-side.
 * Computed by CaseEngine.pendingTransition, a non-mutating mirror of
 * advanceIfExitable's own exitability/branch-target logic (Task 15).
 */
public record AvailableTransitionView(UUID nextStageId, String nextStageName, boolean approvalPending) {}
