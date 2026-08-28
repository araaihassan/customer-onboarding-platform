"use client";

import { useState } from "react";
import { StageAccordion } from "@/components/ui/StageAccordion";
import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import type { Approval, MilestoneRoadmap, Participant, StageRoadmap } from "@/lib/api/cases";
import { t } from "@/lib/i18n";
import { MilestoneRow } from "./MilestoneRow";

type StageStatus = "complete" | "active" | "upcoming";

const ROLE_BY_STAGE_STATUS: Record<StageStatus, StatusRole> = {
  complete: "ok",
  active: "warn",
  upcoming: "neutral",
};

/**
 * `StageRoadmapView` carries no status/progress of its own (unlike the design
 * mockup's aspirational `Stage` shape in STATE_AND_DATA.md) -- both are derived
 * here from the milestones actually returned, the same way `MilestoneRow`
 * already derives "overdue" client-side rather than expecting a server field.
 */
function stageStatus(milestones: MilestoneRoadmap[]): StageStatus {
  if (milestones.length > 0 && milestones.every((m) => m.status === "DONE" || m.status === "SKIPPED")) {
    return "complete";
  }
  if (milestones.some((m) => m.status === "DONE" || m.status === "ACTIVE" || m.status === "BLOCKED")) {
    return "active";
  }
  return "upcoming";
}

function stageProgress(milestones: MilestoneRoadmap[]): number {
  if (milestones.length === 0) return 0;
  const total = milestones.reduce(
    (sum, m) => sum + (m.progressPercent ?? (m.status === "DONE" || m.status === "SKIPPED" ? 100 : 0)),
    0,
  );
  return Math.round(total / milestones.length);
}

function stageMeta(milestones: MilestoneRoadmap[]): string {
  if (milestones.length === 0) return "";
  const done = milestones.filter((m) => m.status === "DONE" || m.status === "SKIPPED").length;
  return t("stage.milestonesProgress", { done: String(done), total: String(milestones.length) });
}

/**
 * Composes one `StageAccordion` (COMPONENTS.md §14) per stage into the journey
 * tab's list (uispecs §5a). Each stage's own expand/collapse state lives here
 * -- `expandedStageIds`, matching STATE_AND_DATA.md's own case-accordion state
 * shape ("case accordion, multi-open") -- and defaults every stage open, so a
 * freshly loaded roadmap shows every milestone the way the pre-StageAccordion
 * layout always did. `MilestoneRow` -- individually expandable in its own
 * right, unchanged -- is the content passed as each accordion's `children`.
 *
 * `participants` and `approvals` are the whole case's, fetched once by the
 * caller and threaded through rather than refetched per milestone.
 */
export function Roadmap({
  caseId = "",
  stages,
  participants = [],
  approvals = [],
}: {
  caseId?: string;
  stages: StageRoadmap[];
  participants?: Participant[];
  approvals?: Approval[];
}) {
  const [expandedStageIds, setExpandedStageIds] = useState<Set<string>>(
    () => new Set(stages.map((stage, index) => stage.id ?? `stage-${index}`)),
  );

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      {stages.map((stage, index) => {
        const milestones = stage.milestones ?? [];
        const stageId = stage.id ?? `stage-${index}`;
        const status = stageStatus(milestones);

        return (
          <StageAccordion
            key={stageId}
            number={index + 1}
            title={stage.name ?? ""}
            meta={stageMeta(milestones)}
            progressPercent={stageProgress(milestones)}
            statusChip={<StatusPill status={t(`stage.status.${status.toUpperCase()}`)} role={ROLE_BY_STAGE_STATUS[status]} />}
            status={status}
            isOpen={expandedStageIds.has(stageId)}
            onToggle={() =>
              setExpandedStageIds((prev) => {
                const next = new Set(prev);
                if (next.has(stageId)) {
                  next.delete(stageId);
                } else {
                  next.add(stageId);
                }
                return next;
              })
            }
          >
            {milestones.map((milestone) => (
              <MilestoneRow
                key={milestone.id}
                caseId={caseId}
                milestone={milestone}
                participants={participants}
                approvals={approvals}
              />
            ))}
          </StageAccordion>
        );
      })}
    </div>
  );
}
