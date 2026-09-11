"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { EmptyState } from "@/components/ui/States";
import { StatusPill, type StatusRole } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { useDecideRevision, type PlanRevision } from "@/lib/api/plans";
import { t } from "@/lib/i18n";

const ROLE_BY_STATUS: Record<string, StatusRole> = {
  ISSUED: "warn",
  APPROVED: "ok",
  REJECTED: "risk",
  SUPERSEDED: "neutral",
};

/**
 * Gate 2's revision history (sub-project 3A Task 31), newest first --
 * `usePlanRevisions`'s own doc. Revision numbers and dates are mono
 * (CLAUDE.md's machine-value rule); everything a person wrote (the issue
 * note, the decision note) is not. Only the single ISSUED revision -- there
 * is at most one at a time, `PlanRevisionService.issue` supersedes any prior
 * one -- ever shows decide controls, and only to a `plan.approve_schedule`
 * holder.
 */
export function PlanRevisionList({
  caseId,
  revisions,
  canDecide,
}: {
  caseId: string;
  revisions: PlanRevision[];
  canDecide: boolean;
}) {
  if (revisions.length === 0) {
    return <EmptyState title={t("plan.revision.list.empty")} />;
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      {revisions.map((revision) => (
        <div
          key={revision.id}
          className="bg-surface border border-line"
          style={{ borderRadius: "var(--ob-card-radius)", padding: "var(--ob-space-13)" }}
        >
          <div className="flex items-center justify-between flex-wrap" style={{ gap: "var(--ob-space-8)" }}>
            <span className="text-ink" style={MONO_TEXT}>
              {t("plan.revision.number", { number: String(revision.revisionNumber ?? "") })}
            </span>
            {revision.status && (
              <StatusPill status={t(`plan.revision.status.${revision.status}`)} role={ROLE_BY_STATUS[revision.status] ?? "neutral"} />
            )}
          </div>

          <p className="text-text-faint" style={{ ...MONO_TEXT, marginTop: 4 }}>
            {toDateOnly(revision.issuedAt)}
          </p>

          {revision.issueNote && (
            <p className="text-text-muted" style={{ ...BODY_TEXT, marginTop: "var(--ob-space-4)" }}>
              {revision.issueNote}
            </p>
          )}
          {revision.decisionNote && (
            <p className="text-text-muted" style={{ ...BODY_TEXT, marginTop: "var(--ob-space-4)" }}>
              {revision.decisionNote}
            </p>
          )}

          {revision.status === "ISSUED" && canDecide && (
            <DecideRevisionControls caseId={caseId} revisionId={revision.id!} />
          )}
        </div>
      ))}
    </div>
  );
}

/** Records gate 2's decision on behalf of the customer -- sub-project 7's portal gives the customer their own button; until then this is the only path. */
function DecideRevisionControls({ caseId, revisionId }: { caseId: string; revisionId: string }) {
  const decide = useDecideRevision();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();

  function submit(outcome: "APPROVED" | "REJECTED") {
    setError(undefined);
    decide.mutate(
      { caseId, revisionId, outcome, note: note.trim() || undefined },
      { onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")) },
    );
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-8)", marginTop: "var(--ob-space-11)" }}>
      <TextareaField label={t("plan.revision.decide.note")} value={note} rows={2} onChange={(e) => setNote(e.target.value)} />
      <div className="flex" style={{ gap: "var(--ob-space-8)" }}>
        <Button type="button" disabled={decide.isPending} onClick={() => submit("APPROVED")}>
          {t("plan.revision.decide.approve")}
        </Button>
        <Button
          type="button"
          variant="secondary"
          disabled={decide.isPending}
          style={{ color: "var(--ob-risk-fg)", borderColor: "var(--ob-risk-fg)" }}
          onClick={() => submit("REJECTED")}
        >
          {t("plan.revision.decide.reject")}
        </Button>
      </div>
      {error && (
        <p role="alert" style={{ ...BODY_TEXT, color: "var(--ob-risk-fg)" }}>
          {error}
        </p>
      )}
    </div>
  );
}

function toDateOnly(iso: string | undefined): string {
  return iso ? iso.slice(0, 10) : "—";
}

const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;
