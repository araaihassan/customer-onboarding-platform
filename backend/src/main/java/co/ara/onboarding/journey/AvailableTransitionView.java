package co.ara.onboarding.journey;

import java.util.UUID;

/**
 * Non-null on CaseView only when the current stage is exitable but auto_advance is
 * false: the engine has already computed the transition and is waiting for someone
 * with case.advance. The builder's "Advance" button binds to this rather than
 * recomputing exitability client-side. Populated starting Task 15 -- CaseEngine's
 * advanceIfExitable is still a no-op stub as of Task 13/14, so every CaseView built
 * today carries null here.
 */
public record AvailableTransitionView(UUID nextStageId, String nextStageName, boolean approvalPending) {}
