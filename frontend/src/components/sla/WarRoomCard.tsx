"use client";

import Link from "next/link";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import type { ExceptionCard } from "@/lib/api/sla";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { formatEscalationNote } from "./escalationNote";
import { SlaChip } from "./SlaChip";
import { days, formatSlaClock } from "./formatSlaClock";

const dataFont = { fontFamily: "var(--ob-font-family-data)" } as const;

/**
 * One exception on the war-room board (SCREENS section 4). Flat: a border, no shadow; the border
 * is the risk tone only for a breached clock. Names the server could not resolve (out-of-scope
 * customer, stage or owner) render as nothing, never as an id. The three actions call back; the
 * page owns the dialogs.
 */
export function WarRoomCard({
  card,
  slug,
  onReassign,
  onForceComplete,
  onRemind,
}: {
  card: ExceptionCard;
  slug: string;
  onReassign: (card: ExceptionCard) => void;
  onForceComplete: (card: ExceptionCard) => void;
  onRemind: (card: ExceptionCard) => void;
}) {
  // The same permission the case workspace checks before offering force-complete (MilestoneRow).
  const canForceComplete = useHasPermission("milestone.force_complete");
  const clock = card.clock ?? {};
  const breached = clock.state === "BREACHED";
  const note = noteFor(card);
  // DOMAIN_RULES L146: every exception states pause eligibility, independent of the note.
  const ineligible = clock.pauseEligible === false && clock.state !== "PAUSED";
  const caseName = card.caseName ?? "";
  // Without a customer id there is no valid case route: render the name as plain text, no link.
  const href = card.customerId && card.caseId ? `/t/${slug}/customers/${card.customerId}/cases/${card.caseId}` : undefined;

  return (
    <article
      data-testid="war-room-card"
      className="bg-surface"
      style={{
        border: `1px solid ${breached ? "var(--ob-risk-border)" : "var(--ob-line)"}`,
        borderRadius: "var(--ob-radius-11)",
        overflow: "hidden",
      }}
    >
      <div style={{ padding: "13px 14px 11px" }} className="flex flex-col gap-2">
        <div>
          <SlaChip clock={clock} />
        </div>
        <div>
          <h3 className="text-ink" style={{ font: "600 15px/1.3 var(--ob-font-family-ui)" }}>
            {href ? (
              <Link href={href} className="hover:underline">
                {caseName}
              </Link>
            ) : (
              caseName
            )}
          </h3>
          {(card.customerName || card.stageName) && (
            <p className="text-text-subtle" style={{ font: "12px/1.4 var(--ob-font-family-ui)" }}>
              {[card.customerName, card.stageName].filter(Boolean).join(" · ")}
            </p>
          )}
        </div>
        <dl style={{ font: "11.5px/1.5 var(--ob-font-family-ui)", margin: 0 }} className="flex flex-col gap-1">
          <div className="flex justify-between">
            <dt className="text-text-subtle">{t("sla.card.elapsed")}</dt>
            <dd style={dataFont}>{`${days(clock.elapsedDays)} / ${clock.targetDays ?? 0}`}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-text-subtle">{t("sla.card.clock")}</dt>
            <dd
              data-testid="war-room-clock"
              style={{ fontWeight: 700, color: `var(--ob-${formatSlaClock(clock).tone}-fg)`, fontFamily: "var(--ob-font-family-data)" }}
            >
              {clockWord(card)}
            </dd>
          </div>
          <div className="flex justify-between items-center">
            <dt className="text-text-subtle">{t("sla.card.owner")}</dt>
            <dd className="flex items-center gap-1.5">
              {card.ownerName && <Avatar name={card.ownerName} kind="person" size={18} />}
              <span>{card.ownerName ?? t("sla.card.unassigned")}</span>
            </dd>
          </div>
        </dl>
        {ineligible && (
          <p data-testid="war-room-ineligible" className="text-text-subtle" style={{ font: "11.5px/1.4 var(--ob-font-family-ui)" }}>
            {t("sla.callout.ineligible")}
          </p>
        )}
        {note && (
          <p
            data-testid="war-room-note"
            style={{
              background: "var(--ob-surface-sunken)",
              borderRadius: "var(--ob-radius-8)",
              padding: "7px 9px",
              font: "11.5px/1.45 var(--ob-font-family-ui)",
            }}
          >
            {note}
          </p>
        )}
      </div>
      <div
        className="flex items-center gap-2 flex-wrap"
        style={{
          padding: "9px 14px",
          borderTop: "1px solid var(--ob-line-faint)",
          background: "var(--ob-surface-sunken)",
        }}
      >
        {href && (
          <Link
            href={href}
            className="inline-flex items-center hover:underline"
            style={{ font: "500 11.5px/1.2 var(--ob-font-family-ui)", color: "var(--ob-ink)", minHeight: 27 }}
          >
            {t("sla.card.openCase")}
          </Link>
        )}
        {card.hasOpenRequests && (
          <Button type="button" variant="small-secondary" onClick={() => onRemind(card)}>
            {t("sla.card.remind")}
          </Button>
        )}
        <Button type="button" variant="small-secondary" onClick={() => onReassign(card)}>
          {t("sla.card.reassign")}
        </Button>
        {canForceComplete && (
          <Button type="button" variant="danger-outline" className="ml-auto" onClick={() => onForceComplete(card)}>
            {t("sla.card.forceComplete")}
          </Button>
        )}
      </div>
    </article>
  );
}

function noteFor(card: ExceptionCard): string | null {
  const latest = card.escalations?.[0];
  if (latest) return formatEscalationNote(latest);
  const clock = card.clock;
  if (clock?.state === "PAUSED" && clock.pauseReason) return t(`sla.callout.reason.${clock.pauseReason}`);
  return null;
}

/** The clock's state as a word plus the server's numbers, truncated by the same `days` the chip uses. */
function clockWord(card: ExceptionCard): string {
  const clock = card.clock ?? {};
  switch (clock.state) {
    case "PAUSED":
      return t("sla.card.clock.paused", { days: days(clock.pausedDays) });
    case "BREACHED":
      return t("sla.card.clock.breached", { days: days((clock.elapsedDays ?? 0) - (clock.targetDays ?? 0)) });
    case "MET":
      return t("sla.card.clock.met");
    default:
      return clock.dueToday ? t("sla.card.clock.dueToday") : t("sla.card.clock.running");
  }
}
