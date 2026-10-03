import type { Approval, MilestoneStatus } from "@/lib/api/cases";

/** The milestone's own pending FORCE_COMPLETE approval, if one is already waiting on a second person. */
export function pendingForceComplete(approvals: Approval[], milestoneId: string | undefined): Approval | undefined {
  return approvals.find((a) => a.kind === "FORCE_COMPLETE" && a.milestoneId === milestoneId && a.status === "PENDING");
}

/**
 * Whether a force-complete may still be requested for a milestone: not DONE, not SKIPPED, and no
 * request already pending. The server checks only reason and scope, so every UI path that offers
 * force-complete (the case workspace's MilestoneRow and the SLA war room) shares this one rule.
 */
export function isForceCompletable(status: MilestoneStatus, pending: Approval | undefined): boolean {
  return status !== "DONE" && status !== "SKIPPED" && !pending;
}
