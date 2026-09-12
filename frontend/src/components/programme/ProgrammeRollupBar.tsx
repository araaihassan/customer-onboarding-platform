import { ProgressBar } from "@/components/ui/ProgressBar";
import { t } from "@/lib/i18n";

/**
 * The programme's duration-weighted rollup (design spec §5.6, §6.4).
 *
 * The rollup is computed only over the journeys the reader can see — `journeysCovered`
 * is that count, never the programme's real total, and it is stated in words beside the
 * percentage rather than left implicit. §6.4's own point: a scope-limited view must read
 * as partial, not as wrong. A viewer who can see every journey the programme actually has
 * reads the true figure without knowing it was ever filtered; one who cannot reads "62%
 * across 3 journeys" instead of a number that looks complete but is not.
 *
 * `journeysCovered === 0` renders no bar at all -- a 0% fill next to "Progress" would read
 * as "this programme has not started", which is a different claim from "there is nothing
 * here to compute a percentage over" (no journey the participant is allowed to open, or
 * none linked yet). Collapsing those into one zeroed bar is exactly the shape review
 * finding 9's missing `role="progressbar"` problem was about, one level up: a number with
 * no honest referent.
 */
export function ProgrammeRollupBar({
  percent,
  journeysCovered,
}: {
  /** 0-100, already rounded server-side, but clamped again here defensively. */
  percent: number;
  journeysCovered: number;
}) {
  if (journeysCovered === 0) {
    return (
      <p
        className="text-text-subtle"
        style={{ font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)" }}
      >
        {t("programme.rollup.empty")}
      </p>
    );
  }

  const clamped = Math.max(0, Math.min(100, Math.round(percent)));

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      <div className="flex items-baseline flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
        {/* A machine-generated figure: Spline Sans Mono. */}
        <span
          className="text-ink"
          style={{
            font: "600 var(--ob-type-card-title-size)/var(--ob-type-card-title-line) var(--ob-font-family-data)",
          }}
        >
          {clamped}%
        </span>
        <span
          className="text-text-subtle"
          style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {t("programme.rollup.coverage", { count: String(journeysCovered) })}
        </span>
      </div>
      <ProgressBar value={clamped} label={t("programme.rollup.label")} context="case-hero" />
    </div>
  );
}
