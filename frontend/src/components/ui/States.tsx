import type { ReactNode } from "react";
import { t } from "@/lib/i18n";
import { Button } from "./Button";

/**
 * Empty, loading and error states.
 *
 * None of the three exists in the design (COMPONENTS.md §22 lists all three as known gaps
 * implementers must supply, in its own prescribed style) but every list in Tasks 27 and 28 needs
 * them, so they are built once here rather than improvised per screen. `EmptyState`'s shape
 * follows §22's own prescription: a line-strong icon, a 13.5px/600-weight title, and a 12.5px
 * text-subtle explanation of what belongs here and what fills it.
 */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon?: ReactNode;
  title: string;
  description?: string;
  action?: ReactNode;
}) {
  return (
    <div
      className="flex flex-col items-center text-center"
      style={{ padding: "var(--ob-space-40) var(--ob-space-20)", gap: "var(--ob-space-8)" }}
    >
      {/* The graphics-only grey. Valid at 3:1 for a 20px+ mark, never for text -- the new
          system has no dedicated graphics tier, so `line-strong` (the rename map's closest
          3:1-at-20px+ role) takes over from the pre-refactor `--ob-graphic-muted`. */}
      {icon && <div style={{ color: "var(--ob-line-strong)" }}>{icon}</div>}
      <p className="text-ink" style={{ font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-ui)" }}>
        {title}
      </p>
      {description && (
        <p className="text-text-subtle" style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)", maxWidth: "42ch" }}>
          {description}
        </p>
      )}
      {action}
    </div>
  );
}

/**
 * Skeleton rows at the REAL row height, so the layout does not jump when content
 * arrives. The prototype records expected counts in hint-placeholder-count
 * attributes precisely so a skeleton can match them. Fill is `line-faint`, per
 * COMPONENTS.md §22's "skeleton blocks at line-faint, matching the real element's radius
 * and height" -- no opacity trick needed once the fill token is already this pale.
 */
export function SkeletonRows({ rows = 4, height = 44 }: { rows?: number; height?: number }) {
  return (
    <div aria-busy="true" aria-live="polite" aria-label={t("common.loading")}>
      {Array.from({ length: rows }, (_, i) => (
        <div
          key={i}
          className="bg-line-faint"
          style={{
            height,
            borderRadius: "var(--ob-radius-4)",
            marginBottom: "var(--ob-space-8)",
          }}
        />
      ))}
    </div>
  );
}

/**
 * COMPONENTS.md §22's error state: a risk callout with a 12px message and a retry button,
 * `role="alert"` so assistive tech announces it on mount rather than relying on the coloured
 * border alone (design spec §6's accessibility addition).
 */
export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry: () => void;
}) {
  return (
    <div
      role="alert"
      style={{
        border: "1px solid var(--ob-risk-border)",
        background: "var(--ob-risk-bg)",
        borderRadius: "var(--ob-radius-10)",
        padding: "11px 12px",
        display: "flex",
        alignItems: "center",
        gap: "var(--ob-space-11)",
        font: "12px/1.4 var(--ob-font-family-ui)",
        color: "#5c2a24",
      }}
    >
      <span style={{ flex: 1 }}>{message}</span>
      <Button variant="small-secondary" onClick={onRetry}>
        {t("common.retry")}
      </Button>
    </div>
  );
}
