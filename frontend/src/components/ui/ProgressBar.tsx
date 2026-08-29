/**
 * Progress bar (component-specs §5).
 *
 * The accessibility half is the reason this is a component rather than a div with
 * a width. Review finding 9: the prototype renders progress as a bare div whose
 * width is a percentage, so a screen reader gets nothing — and the visible number
 * beside it is separate DOM text, meaning the two can disagree.
 *
 * Here `value` is the single source for both the fill width and aria-valuenow, so
 * they cannot drift apart. That is the whole point of the finding.
 */

type Context = "table-cell" | "stage-summary" | "case-hero" | "portal-sidebar" | "portal-card";

function fillColor(value: number, context: Context): string {
  if (context === "case-hero" || context === "stage-summary") {
    return value >= 100 ? "var(--ob-ok-fg)" : "var(--ob-warn-fg)";
  }
  if (value >= 100) return "var(--ob-ok-fg)";
  if (value > 70) return "var(--ob-accent-fg)";
  if (value > 40) return "var(--ob-warn-fg)";
  return "var(--ob-info-fg)";
}

function getHeight(context: Context): string {
  const heights: Record<Context, string> = {
    "table-cell": "5px",
    "stage-summary": "5px",
    "case-hero": "7px",
    "portal-sidebar": "5px",
    "portal-card": "6px",
  };
  return heights[context];
}

export function ProgressBar({
  value,
  label,
  showPercentage = false,
  context = "table-cell",
}: {
  /** 0–100. Clamped, because a percentage out of range is a caller bug, not a UI state. */
  value: number;
  /** Accessible name — required, since a bar with no name announces only a number. */
  label: string;
  showPercentage?: boolean;
  context?: Context;
}) {
  const clamped = Math.max(0, Math.min(100, Math.round(value)));
  const height = getHeight(context);

  return (
    <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
      <div
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label}
        className="bg-line-faint overflow-hidden flex-1"
        style={{ height, borderRadius: "var(--ob-radius-4)" }}
      >
        <div
          className="h-full"
          // The one animated property, at the token duration. Collapsed under
          // prefers-reduced-motion by tokens.css.
          style={{
            width: `${clamped}%`,
            transition: "width var(--ob-duration-pop) ease",
            background: fillColor(clamped, context),
            borderRadius: "var(--ob-radius-4)",
          }}
        />
      </div>
      {showPercentage && (
        <span
          className="text-text-muted"
          style={{ font: "var(--ob-type-small-print-size)/var(--ob-type-small-print-line) var(--ob-font-family-data)" }}
        >
          {clamped}%
        </span>
      )}
    </div>
  );
}
