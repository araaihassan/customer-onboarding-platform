/**
 * Stage accordion (COMPONENTS.md §14) — the case roadmap's core primitive.
 *
 * Built standalone here. Task 30 converts Roadmap.tsx/StageGroupHeader.tsx/
 * MilestoneRow.tsx to compose it; `children` is left to the caller (the
 * expanded panel's milestone rows) so this primitive doesn't need to know
 * `Milestone`'s shape.
 */
import type { ReactNode } from "react";
import { t } from "@/lib/i18n";
import { ProgressBar } from "./ProgressBar";

export function StageAccordion({
  number,
  title,
  meta,
  progressPercent,
  statusChip,
  isOpen,
  onToggle,
  children,
  status = "upcoming",
}: {
  number: number;
  title: string;
  meta: string;
  progressPercent: number;
  statusChip: ReactNode;
  isOpen: boolean;
  onToggle: () => void;
  children: ReactNode;
  status?: "complete" | "active" | "upcoming";
}) {
  const railFill = status === "complete" ? "var(--ob-ok-fg)" : status === "active" ? "var(--ob-warn-fg)" : "var(--ob-line-soft)";
  return (
    <div style={{ display: "flex", gap: "var(--ob-space-11)" }}>
      <div style={{ display: "flex", flexDirection: "column", alignItems: "center", width: 26 }}>
        <span
          style={{
            width: 24, height: 24, borderRadius: "var(--ob-radius-full)",
            background: railFill,
            color: status === "upcoming" ? "var(--ob-text-subtle)" : "var(--ob-canvas)",
            display: "grid", placeItems: "center",
            font: "600 11px/1 var(--ob-font-family-ui)",
          }}
        >
          {status === "complete" ? "✓" : number}
        </span>
        <span style={{ flex: 1, width: 2, margin: "4px 0", background: railFill }} />
      </div>
      <div style={{ flex: 1 }}>
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={isOpen}
          className="w-full text-left bg-surface hover:bg-surface-sunken"
          style={{
            border: "1px solid var(--ob-line)",
            borderRadius: "var(--ob-radius-10)",
            padding: "12px 14px",
            display: "flex",
            alignItems: "center",
            gap: "var(--ob-space-12)",
          }}
        >
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ font: `600 14px/1.3 var(--ob-font-family-ui)`, letterSpacing: "-0.015em", color: "var(--ob-ink)" }}>
              {title}
            </div>
            {meta && (
              <div style={{ font: "11.5px/1.3 var(--ob-font-family-ui)", color: "var(--ob-text-subtle)" }}>
                {meta}
              </div>
            )}
          </div>
          <div style={{ width: 80 }}>
            <ProgressBar value={progressPercent} label={title} context="stage-summary" />
          </div>
          {statusChip}
          <span aria-hidden="true" style={{ transform: isOpen ? "rotate(180deg)" : "none" }}>▾</span>
        </button>
        {isOpen && (
          <div
            style={{
              marginTop: "var(--ob-space-7)",
              border: "1px solid var(--ob-line)",
              borderRadius: "var(--ob-radius-10)",
              background: "var(--ob-surface-sunken)",
              animation: `om-pop var(--ob-duration-pop) var(--ob-ease-default)`,
            }}
          >
            <div
              style={{
                padding: "10px 14px",
                font: `500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)`,
                letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
                textTransform: "uppercase",
                color: "var(--ob-text-faint)",
              }}
            >
              {t("stage.milestonesLabel")}
            </div>
            {children}
          </div>
        )}
      </div>
    </div>
  );
}
