"use client";

import { useEffect, useState } from "react";
import { ForceCompleteDialog } from "@/components/journey/ForceCompleteDialog";
import { isForceCompletable, pendingForceComplete } from "@/components/journey/forceCompleteEligibility";
import { useApprovals, useRoadmap } from "@/lib/api/cases";
import { ForceCompleteMilestonePicker } from "./ForceCompleteMilestonePicker";

/**
 * Force-complete from the war room. A card's escalation history can name a milestone of any age,
 * and the server does not check that it is still open, so the escalated milestone is used only
 * after the roadmap and approvals confirm it can still be force-completed (the case workspace's
 * own rule). Otherwise, or while that is unknown, the picker is shown. Once a target is settled it
 * is latched, so the post-request refetch cannot swap the open dialog for the picker.
 */
export function ForceCompleteFlow({
  caseId,
  stageName,
  escalatedMilestoneId,
  onClose,
}: {
  caseId: string;
  stageName?: string;
  escalatedMilestoneId?: string;
  onClose: () => void;
}) {
  const roadmap = useRoadmap(caseId);
  const approvals = useApprovals(caseId);
  const [target, setTarget] = useState<string>();

  const milestone = (roadmap.data?.stages ?? []).flatMap((s) => s.milestones ?? []).find((m) => m.id === escalatedMilestoneId);
  const escalatedUsable =
    Boolean(escalatedMilestoneId) &&
    Boolean(milestone) &&
    approvals.data !== undefined &&
    isForceCompletable(milestone?.status ?? "PENDING", pendingForceComplete(approvals.data, escalatedMilestoneId));

  useEffect(() => {
    if (target === undefined && escalatedUsable) setTarget(escalatedMilestoneId);
  }, [target, escalatedUsable, escalatedMilestoneId]);

  const chosen = target ?? (escalatedUsable ? escalatedMilestoneId : undefined);
  if (chosen) return <ForceCompleteDialog caseId={caseId} milestoneId={chosen} onClose={onClose} />;
  return <ForceCompleteMilestonePicker caseId={caseId} stageName={stageName} onPick={setTarget} onClose={onClose} />;
}
