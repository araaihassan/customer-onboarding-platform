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
 *
 * A SKIPPED milestone still means the stage was *reached* -- something in it
 * ran, even if this particular milestone didn't -- so it counts toward
 * "active", same as DONE/ACTIVE/BLOCKED. Without this, a stage holding only
 * [SKIPPED, PENDING] milestones falls through to "upcoming" despite clearly
 * having been entered.
 */
function stageStatus(milestones: MilestoneRoadmap[]): StageStatus {
  if (milestones.length > 0 && milestones.every((m) => m.status === "DONE" || m.status === "SKIPPED")) {
    return "complete";
  }
  if (milestones.some((m) => m.status === "DONE" || m.status === "ACTIVE" || m.status === "BLOCKED" || m.status === "SKIPPED")) {
    return "active";
  }
  return "upcoming";
}

/**
 * CLAUDE.md's sub-project-2 invariant 10: "Skipped milestones contribute to
 * neither progress numerator nor denominator." SKIPPED milestones are
 * filtered out entirely before averaging, not counted as 100% -- otherwise a
 * [DONE, SKIPPED, PENDING] stage would read 67% instead of the
 * case-engine-honored 50%. A stage left with nothing after filtering (every
 * milestone SKIPPED) reads 100%, matching `stageStatus`'s own "complete" call
 * for that same shape.
 */
function countedMilestones(milestones: MilestoneRoadmap[]): MilestoneRoadmap[] {
  return milestones.filter((m) => m.status !== "SKIPPED");
}

function stageProgress(milestones: MilestoneRoadmap[]): number {
  const counted = countedMilestones(milestones);
  if (counted.length === 0) return milestones.length === 0 ? 0 : 100;
  const total = counted.reduce((sum, m) => sum + (m.progressPercent ?? (m.status === "DONE" ? 100 : 0)), 0);
  return Math.round(total / counted.length);
}

function stageMeta(milestones: MilestoneRoadmap[]): string {
  const counted = countedMilestones(milestones);
  if (counted.length === 0) return "";
  const done = counted.filter((m) => m.status === "DONE").length;
  return t("stage.milestonesProgress", { done: String(done), total: String(counted.length) });
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
