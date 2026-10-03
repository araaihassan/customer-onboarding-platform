"use client";

import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { SkeletonRows } from "@/components/ui/States";
import { useRoadmap } from "@/lib/api/cases";
import { t } from "@/lib/i18n";
import { InlineError, SlaEyebrow } from "./dialogParts";

/**
 * The step before the existing ForceCompleteDialog when the war-room card carries no milestone
 * escalation to name the target. Lists the current stage's incomplete milestones from the
 * roadmap; the roadmap does not mark the current stage, so the card's stage name picks it (when it
 * matches nothing, every stage's incomplete milestones are offered rather than none).
 */
export function ForceCompleteMilestonePicker({
  caseId,
  stageName,
  onPick,
  onClose,
}: {
  caseId: string;
  stageName?: string;
  onPick: (milestoneId: string) => void;
  onClose: () => void;
}) {
  const roadmap = useRoadmap(caseId);
  const stages = roadmap.data?.stages ?? [];
  const current = stageName ? stages.filter((s) => s.name === stageName) : [];
  const scoped = current.length > 0 ? current : stages;
  const milestones = scoped
    .flatMap((s) => s.milestones ?? [])
    .filter((m) => m.status !== "DONE" && m.status !== "SKIPPED");

  let body;
  if (roadmap.isError) {
    body = <InlineError>{t("sla.forceComplete.error")}</InlineError>;
  } else if (roadmap.isLoading) {
    body = <SkeletonRows rows={3} height={36} />;
  } else if (milestones.length === 0) {
    body = (
      <p className="text-text-subtle" style={{ font: "13px/1.5 var(--ob-font-family-ui)" }}>
        {t("sla.forceComplete.none")}
      </p>
    );
  } else {
    body = (
      <>
        <p className="text-text-subtle" style={{ font: "12px/1.5 var(--ob-font-family-ui)", marginBottom: "var(--ob-space-12)" }}>
          {stageName ? t("sla.forceComplete.pickHelp", { stage: stageName }) : t("sla.forceComplete.pickHelpAny")}
        </p>
        <ul className="flex flex-col" style={{ listStyle: "none", padding: 0, margin: 0, gap: "var(--ob-space-8)" }}>
          {milestones.map((m) => (
            <li key={m.id}>
              <Button type="button" variant="secondary" className="w-full justify-start" onClick={() => onPick(m.id ?? "")}>
                {m.name}
              </Button>
            </li>
          ))}
        </ul>
      </>
    );
  }

  return (
    <Dialog title={t("sla.forceComplete.pickTitle")} eyebrow={<SlaEyebrow />} onClose={onClose} maxWidth={480}>
      {body}
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
