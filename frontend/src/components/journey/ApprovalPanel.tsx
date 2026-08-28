"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { TextareaField } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, useDecideApproval, type Approval } from "@/lib/api/cases";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * Q5's second mechanism, rendered: the decider must hold the relevant
 * permission, and each kind decides through its own endpoint (spec §5.4,
 * §7.2) -- `useDecideApproval` already picks between them by `kind`, so this
 * only has to pick the matching permission to gate the controls on.
 *
 * Self-approval is refused server-side, not pre-checked here: a 403 renders
 * as the explanation, the same shape `RequirementList` already gives a
 * write-scope refusal, rather than the panel quietly hiding the buttons for
 * whoever happens to match the requester id.
 */
const DECIDE_PERMISSION: Record<string, string> = {
  STAGE_EXIT: "approval.decide",
  FORCE_COMPLETE: "milestone.force_approve",
};

export function ApprovalPanel({ caseId, approval }: { caseId: string; approval: Approval }) {
  const canDecide = useHasPermission(DECIDE_PERMISSION[approval.kind ?? "FORCE_COMPLETE"] ?? "milestone.force_approve");
  const decide = useDecideApproval();
  const [note, setNote] = useState("");
  const [error, setError] = useState<string>();

  function submit(approve: boolean) {
    setError(undefined);
    decide.mutate(
      { caseId, approvalId: approval.id!, kind: approval.kind!, approve, note: note.trim() || undefined },
      {
        onError: (err) => setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error")),
      },
    );
  }

  return (
    <div
      className="flex flex-col bg-surface-sunken"
      style={{ gap: "var(--ob-space-8)", padding: "var(--ob-space-11)", borderRadius: "var(--ob-radius-10)" }}
    >
      <p
        className="text-ink"
        style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
      >
        {t("approval.pendingTitle")}
      </p>
      {approval.reason && (
        <p
          className="text-text-muted"
          style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {approval.reason}
        </p>
      )}

      {canDecide && approval.status === "PENDING" && (
        <>
          <TextareaField
            label={t("approval.note")}
            value={note}
            rows={2}
            onChange={(e) => setNote(e.target.value)}
          />
          <div className="flex" style={{ gap: "var(--ob-space-8)" }}>
            <Button type="button" disabled={decide.isPending} onClick={() => submit(true)}>
              {t("approval.approve")}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={decide.isPending}
              style={{ color: "var(--ob-risk-fg)", borderColor: "var(--ob-risk-fg)" }}
              onClick={() => submit(false)}
            >
              {t("approval.reject")}
            </Button>
          </div>
        </>
      )}

      {error && (
        <p
          role="alert"
          style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {error}
        </p>
      )}
    </div>
  );
}
