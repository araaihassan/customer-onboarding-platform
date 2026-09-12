"use client";

import { SkeletonRows } from "@/components/ui/States";
import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import { useRevisionDiff, type PlanRevisionDiffRow } from "@/lib/api/plans";
import { t } from "@/lib/i18n";

const ROLE_BY_CHANGE: Record<string, StatusRole> = {
  ADDED: "ok",
  REMOVED: "risk",
  DATE_CHANGED: "warn",
  OWNER_CHANGED: "accent",
  UNCHANGED: "neutral",
};

/**
 * The delta between two schedule revisions (sub-project 3A Task 31, Q22/Q23
 * gate 2) -- `PlanRevisionService`'s own server-computed diff, matched by
 * `milestoneDefinitionId` so a renamed milestone still tracks across
 * revisions. Unchanged rows are dropped: a diff exists to show what moved,
 * and a milestone identical between the two snapshots is not something an
 * approver needs to re-read every time.
 *
 * A moved date is never colour-only (CLAUDE.md's four decisions): the word
 * "Moved" always accompanies the amber highlight, next to the two dates
 * themselves in mono.
 */
export function PlanRevisionDiff({
  caseId,
  revisionId,
  againstId,
}: {
  caseId: string;
  revisionId: string;
  againstId: string;
}) {
  const diff = useRevisionDiff(caseId, revisionId, againstId);

  if (diff.isLoading) return <SkeletonRows rows={2} height={40} />;
  if (diff.isError) return null;

  const rows = (diff.data?.rows ?? []).filter((row) => row.changeKind !== "UNCHANGED");
  if (rows.length === 0) return null;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <h5
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
        }}
      >
        {t("plan.diff.title")}
      </h5>

      <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
        {rows.map((row) => (
          <DiffRow key={row.milestoneDefinitionId} row={row} />
        ))}
      </div>
    </div>
  );
}

function DiffRow({ row }: { row: PlanRevisionDiffRow }) {
  const kind = row.changeKind ?? "UNCHANGED";

  return (
    <div
      className="flex items-center justify-between flex-wrap bg-surface border border-line"
      style={{ gap: "var(--ob-space-11)", padding: "var(--ob-space-8) var(--ob-space-11)", borderRadius: "var(--ob-radius-8)" }}
    >
      <span className="text-ink" style={BODY_TEXT}>
        {row.milestoneName}
      </span>

      <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
        {kind === "DATE_CHANGED" && (
          <span className="text-text-muted" style={MONO_TEXT}>
            {row.previousDueDate ?? "—"} → {row.currentDueDate ?? "—"}
          </span>
        )}
        <StatusPill status={t(`plan.diff.changeKind.${kind}`)} role={ROLE_BY_CHANGE[kind] ?? "neutral"} />
      </div>
    </div>
  );
}

const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;
