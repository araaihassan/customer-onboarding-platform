"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { ForceCompleteFlow } from "@/components/sla/ForceCompleteFlow";
import { ReassignDialog } from "@/components/sla/ReassignDialog";
import { RemindCustomerDialog } from "@/components/sla/RemindCustomerDialog";
import { ClockIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { WarRoomCard } from "@/components/sla/WarRoomCard";
import { EmptyState, ErrorState, SkeletonRows } from "@/components/ui/States";
import { slaKeys, useSlaExceptions, type ExceptionCard } from "@/lib/api/sla";
import { t } from "@/lib/i18n";

type Dialog = { kind: "reassign" | "remind" | "force"; card: ExceptionCard };

/** The newest escalation that names a milestone: a candidate only, ForceCompleteFlow validates it. */
function escalatedMilestoneId(card: ExceptionCard): string | undefined {
  return card.escalations?.find((e) => e.subjectType === "MILESTONE" && e.subjectId)?.subjectId;
}

type Column = { key: "breached" | "dueToday" | "watch"; dot: string };
const COLUMNS: Column[] = [
  { key: "breached", dot: "var(--ob-risk-fg)" },
  { key: "dueToday", dot: "var(--ob-warn-fg)" },
  { key: "watch", dot: "var(--ob-info-fg)" },
];

const STRIP = [
  { key: "breached", color: "var(--ob-risk-fg)" },
  { key: "dueToday", color: "var(--ob-warn-fg)" },
  { key: "clocksPaused", color: "var(--ob-info-fg)" },
  { key: "autoEscalated", color: "var(--ob-ink)" },
] as const;

/**
 * The SLA war room (SCREENS section 4): a summary strip, three triage columns and the policy
 * panel. The server decides what is visible and which column a clock belongs in; this renders
 * what it returns. Polling lives in `useSlaExceptions`. The card actions open one dialog at a
 * time; closing any of them refetches the board, since each can move a card between columns.
 */
export default function SlaWarRoomPage() {
  const { slug } = useParams<{ slug: string }>();
  const query = useSlaExceptions();
  const queryClient = useQueryClient();
  const [dialog, setDialog] = useState<Dialog | null>(null);
  useSetPageHeader(t("sla.title"));

  if (query.isError) return <ErrorState message={t("sla.error")} onRetry={() => void query.refetch()} />;
  if (query.isLoading || !query.data) return <SkeletonRows rows={5} height={96} />;

  function closeDialog() {
    setDialog(null);
    void queryClient.invalidateQueries({ queryKey: slaKeys.exceptions() });
  }

  const data = query.data;
  const summary = data.summary ?? {};
  const cards: Record<Column["key"], ExceptionCard[]> = {
    breached: data.breached ?? [],
    dueToday: data.dueToday ?? [],
    watch: data.watch ?? [],
  };
  const allEmpty = COLUMNS.every((c) => cards[c.key].length === 0);

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <header>
        <p
          className="text-text-subtle"
          style={{ font: "500 11px/1.4 var(--ob-font-family-data)", letterSpacing: "0.04em" }}
        >
          {t("sla.eyebrow", { calendar: data.calendarName ?? "" }).toUpperCase()}
        </p>
        <p className="text-text-subtle" style={{ font: "13px/1.5 var(--ob-font-family-ui)", maxWidth: "70ch" }}>
          {t("sla.sub")}
        </p>
      </header>

      <ul
        aria-label={t("sla.summary.label")}
        className="grid grid-cols-2 min-[900px]:grid-cols-4 gap-3"
        style={{ listStyle: "none", padding: 0, margin: 0 }}
      >
        {STRIP.map(({ key, color }) => (
          <li
            key={key}
            data-testid={`sla-summary-${key}`}
            className="bg-surface"
            style={{ border: "1px solid var(--ob-line)", borderRadius: "var(--ob-radius-11)", padding: "12px 14px" }}
          >
            <span className="text-text-subtle" style={{ font: "500 11px/1.4 var(--ob-font-family-data)", letterSpacing: "0.04em" }}>
              {t(`sla.summary.${key}`)}
            </span>
            <span data-testid="sla-summary-value" style={{ font: "600 28px/1.2 var(--ob-font-family-data)", color, display: "block" }}>
              {summary[key] ?? 0}
            </span>
          </li>
        ))}
      </ul>

      {allEmpty ? (
        <EmptyState icon={<ClockIcon size={28} />} title={t("sla.empty.title")} description={t("sla.empty.description")} />
      ) : (
        <div className="grid grid-cols-1 min-[900px]:grid-cols-3 gap-4 items-start" data-testid="sla-columns">
          {COLUMNS.map(({ key, dot }) => (
            <section key={key} aria-labelledby={`sla-col-${key}`} className="flex flex-col gap-3">
              <h2 id={`sla-col-${key}`} className="flex items-center gap-2" style={{ font: "600 13px/1.4 var(--ob-font-family-ui)" }}>
                <span aria-hidden="true" style={{ width: 8, height: 8, borderRadius: "50%", background: dot }} />
                {t(`sla.column.${key}`)}
                <span className="text-text-subtle" style={{ font: "11.5px/1.4 var(--ob-font-family-data)" }}>
                  {cards[key].length}
                </span>
              </h2>
              {cards[key].length === 0 ? (
                <p className="text-text-subtle" style={{ font: "12px/1.4 var(--ob-font-family-ui)" }}>
                  {t("sla.column.empty")}
                </p>
              ) : (
                cards[key].map((card, i) => (
                  <WarRoomCard
                    key={`${card.caseId}-${card.clock?.clockId ?? i}`}
                    card={card}
                    slug={slug}
                    onReassign={(c) => setDialog({ kind: "reassign", card: c })}
                    onForceComplete={(c) => setDialog({ kind: "force", card: c })}
                    onRemind={(c) => setDialog({ kind: "remind", card: c })}
                  />
                ))
              )}
            </section>
          ))}
        </div>
      )}

      <p
        style={{
          border: "1px dashed var(--ob-line-strong)",
          borderRadius: "var(--ob-radius-11)",
          padding: "12px 14px",
          font: "12px/1.5 var(--ob-font-family-ui)",
        }}
      >
        {t("sla.policy")}
      </p>

      {dialog?.kind === "reassign" && dialog.card.caseId && <ReassignDialog caseId={dialog.card.caseId} onClose={closeDialog} />}
      {dialog?.kind === "remind" && dialog.card.caseId && <RemindCustomerDialog caseId={dialog.card.caseId} onClose={closeDialog} />}
      {dialog?.kind === "force" && dialog.card.caseId && (
        <ForceCompleteFlow
          caseId={dialog.card.caseId}
          stageName={dialog.card.stageName}
          escalatedMilestoneId={escalatedMilestoneId(dialog.card)}
          onClose={closeDialog}
        />
      )}
    </section>
  );
}
