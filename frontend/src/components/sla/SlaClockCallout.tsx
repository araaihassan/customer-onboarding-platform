import type { ReactNode } from "react";
import { formatDate } from "@/lib/format/date";
import { ClockIcon, PauseCircleIcon } from "@/components/icons";
import { SkeletonRows } from "@/components/ui/States";
import type { SlaClock } from "@/lib/api/sla";
import { t } from "@/lib/i18n";
import { days, formatSlaClock } from "./formatSlaClock";

const MARK = "\u0001";

/** Renders a template with the named params (numbers, dates) in the data font and the prose in the UI font. */
function withData(key: string, params: Record<string, string>, dataKeys: string[]): ReactNode {
  const marked = Object.fromEntries(
    Object.entries(params).map(([k, v]) => [k, dataKeys.includes(k) ? `${MARK}${v}${MARK}` : v]),
  );
  return t(key, marked)
    .split(MARK)
    .map((part, i) =>
      i % 2 === 1 ? (
        <span key={i} style={{ fontFamily: "var(--ob-font-family-data)" }}>
          {part}
        </span>
      ) : (
        part
      ),
    );
}

const subtle = {
  font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
  marginTop: 2,
} as const;

function headlineFor(clock: SlaClock): ReactNode {
  switch (clock.state) {
    case "PAUSED":
      return withData("sla.callout.paused", { days: days(clock.pausedDays) }, ["days"]);
    case "BREACHED":
      return withData(
        "sla.callout.breached",
        { days: days((clock.elapsedDays ?? 0) - (clock.targetDays ?? 0)) },
        ["days"],
      );
    case "MET":
      return withData("sla.callout.met", { days: days(clock.elapsedDays) }, ["days"]);
    default:
      return withData(
        "sla.callout.running",
        { left: days(clock.remainingDays), target: String(clock.targetDays ?? 0) },
        ["left", "target"],
      );
  }
}

/**
 * The case workspace rail's "SLA CLOCK" card (SCREENS section 3). Flat, bordered in the
 * tone of the state (AwaitingApprovalBanner's structure, not its fixed warn colour).
 * `null`/`undefined` is a normal state -- the stage has no clock -- and renders nothing.
 */
export function SlaClockCallout({ clock, loading = false }: { clock: SlaClock | null | undefined; loading?: boolean }) {
  if (loading) return <SkeletonRows rows={2} height={20} />;
  if (!clock) return null;

  const { tone } = formatSlaClock(clock);
  const esc = clock.escalatedTo;
  const Icon = clock.state === "PAUSED" ? PauseCircleIcon : ClockIcon;

  return (
    <div
      data-testid="sla-clock-callout"
      role="status"
      className="flex items-start"
      style={{
        gap: "var(--ob-space-13)",
        padding: "var(--ob-space-13) var(--ob-space-16)",
        borderRadius: "var(--ob-radius-10)",
        background: `var(--ob-${tone}-bg)`,
        border: `1px solid var(--ob-${tone}-border)`,
      }}
    >
      <span aria-hidden="true" style={{ color: `var(--ob-${tone}-fg)`, flexShrink: 0 }}>
        <Icon size={20} />
      </span>
      <div className="min-w-0 flex-1">
        <h3
          className="text-text-muted"
          style={{
            font: "600 var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
            letterSpacing: "0.05em",
          }}
        >
          {t("sla.callout.title")}
        </h3>
        <p
          className="text-ink"
          style={{
            font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            marginTop: 2,
          }}
        >
          {headlineFor(clock)}
        </p>
        {clock.state === "PAUSED" && clock.pauseReason && (
          <p className="text-text-muted" style={subtle}>
            {t(`sla.callout.reason.${clock.pauseReason}`)}
          </p>
        )}
        {clock.pauseEligible === false && (
          <p className="text-text-muted" style={{ ...subtle, letterSpacing: "0.03em" }}>
            {t("sla.callout.ineligible")}
          </p>
        )}
        {clock.state === "BREACHED" && esc && (
          <p className="text-text-muted" style={subtle}>
            {withData(
              esc.name ? "sla.callout.escalatedTo" : "sla.callout.escalatedToAdmins",
              { name: esc.name ?? "", date: esc.at ? formatDate(esc.at) : "—" },
              ["date"],
            )}
          </p>
        )}
        {clock.calendarName && (
          <p className="text-text-muted" style={subtle}>
            {t("sla.callout.calendar", { name: clock.calendarName })}
          </p>
        )}
      </div>
    </div>
  );
}
