import type { Approval, Participant, StageRoadmap } from "@/lib/api/cases";
import { MilestoneRow } from "./MilestoneRow";
import { StageGroupHeader } from "./StageGroupHeader";

/**
 * Composes stage headers and milestone rows into the journey tab's list
 * (uispecs §5a). The decision from design: a stage's own header is
 * suppressed when it holds exactly one milestone of the same name, so a 1:1
 * workflow renders exactly nine milestone rows rather than nine headers each
 * holding one row.
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
  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      {stages.map((stage) => {
        const milestones = stage.milestones ?? [];
        const showHeader = milestones.length !== 1 || milestones[0]?.name !== stage.name;

        return (
          <div key={stage.id} className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
            {showHeader && <StageGroupHeader name={stage.name ?? ""} />}
            {milestones.map((milestone) => (
              <MilestoneRow
                key={milestone.id}
                caseId={caseId}
                milestone={milestone}
                participants={participants}
                approvals={approvals}
              />
            ))}
          </div>
        );
      })}
    </div>
  );
}
