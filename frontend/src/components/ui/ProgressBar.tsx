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
export function ProgressBar({
  value,
  label,
  showPercentage = false,
  size = "inline",
}: {
  /** 0–100. Clamped, because a percentage out of range is a caller bug, not a UI state. */
  value: number;
  /** Accessible name — required, since a bar with no name announces only a number. */
  label: string;
  showPercentage?: boolean;
  size?: "inline" | "large";
}) {
  const clamped = Math.max(0, Math.min(100, Math.round(value)));
  const height = size === "large"
    ? "var(--ob-progress-track-height-lg)"
    : "var(--ob-progress-track-height)";

  return (
    <div className="flex items-center" style={{ gap: "var(--ob-space-8)" }}>
      <div
        role="progressbar"
        aria-valuenow={clamped}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label={label}
        className="bg-bg-inset rounded-bar overflow-hidden flex-1"
        style={{ height }}
      >
        <div
          className="bg-accent rounded-bar h-full"
          // The one animated property, at the token duration. Collapsed under
          // prefers-reduced-motion by tokens.css.
          style={{ width: `${clamped}%`, transition: "width var(--ob-duration-progress) ease" }}
        />
      </div>
      {showPercentage && (
        <span
          className="text-text-muted"
          style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)" }}
        >
          {clamped}%
        </span>
      )}
    </div>
  );
}
