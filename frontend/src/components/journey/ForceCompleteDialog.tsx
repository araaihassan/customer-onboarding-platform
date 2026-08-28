"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, useForceComplete } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/**
 * Q5's first mechanism: `approval.reason` is `NOT NULL`, so the request
 * cannot be built without one -- a required textarea, not an optional field
 * with a placeholder. Submitting creates a PENDING approval; it does not
 * complete the milestone itself, which is why success just closes the
 * dialog rather than showing a "done" state.
 *
 * COMPONENTS.md §18's Modal (force-complete) spec describes an additional
 * approver-picker `<select>` and a "reason exceeds 9 characters" disabled
 * threshold -- neither exists in this codebase (there is no data source an
 * approver `<select>` could be populated from; the second person who decides
 * this request does so later, through `ApprovalPanel`, gated by
 * `approval.decide`/`milestone.force_approve`). Per an explicit ruling on
 * Task 31, that's treated as aspirational spec text describing a feature
 * never built, not a bug for this restyle to invent: the single required
 * `reason` field and its non-empty, post-click validation below are
 * untouched. Only the §18 chrome (eyebrow, risk callout, bordered footer)
 * is adapted onto the dialog as it actually exists.
 */
export function ForceCompleteDialog({
  caseId,
  milestoneId,
  onClose,
}: {
  caseId: string;
  milestoneId: string;
  onClose: () => void;
}) {
  const forceComplete = useForceComplete();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  return (
    <Dialog
      title={t("milestone.forceCompleteDialog.title")}
      eyebrow={
        <p
          style={{
            font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
            letterSpacing: "var(--ob-type-mono-label-sm-tracking)",
            textTransform: "uppercase",
            color: "var(--ob-risk-fg)",
            marginBottom: "var(--ob-space-8)",
          }}
        >
          {t("milestone.forceCompleteDialog.eyebrow")}
        </p>
      }
      onClose={onClose}
      maxWidth={520}
    >
      <div
        role="note"
        style={{
          border: "1px solid var(--ob-risk-border)",
          background: "var(--ob-risk-bg)",
          borderRadius: "var(--ob-radius-10)",
          padding: "11px 12px",
          marginBottom: "var(--ob-space-14)",
          font: "12px/1.4 var(--ob-font-family-ui)",
          color: "var(--ob-risk-fg)",
        }}
      >
        {t("milestone.forceCompleteDialog.warning")}
      </div>

      <TextareaField
        label={t("milestone.forceCompleteDialog.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />

      {forceComplete.isError && (
        <p
          role="alert"
          style={{ color: "var(--ob-risk-fg)", marginTop: "var(--ob-space-11)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
        >
          {forceComplete.error instanceof ApiError ? parseProblemDetail(forceComplete.error.message) : t("common.error")}
        </p>
      )}

      {/* §18's bordered footer, bled to the panel's own edges (Dialog pads
          title+children uniformly by 20px, with no separate footer slot) --
          this chrome belongs to this one dialog, so it stays local rather
          than widening `DialogActions` for every other caller composing it. */}
      <div
        style={{
          margin: `0 calc(-1 * var(--ob-space-20)) calc(-1 * var(--ob-space-20))`,
          padding: `0 var(--ob-space-20) var(--ob-space-13)`,
          borderTop: "1px solid var(--ob-line-soft)",
          background: "var(--ob-surface-sunken)",
          borderRadius: "0 0 var(--ob-radius-13) var(--ob-radius-13)",
        }}
      >
        <DialogActions>
          <Button type="button" variant="secondary" onClick={onClose}>
            {t("common.cancel")}
          </Button>
          <Button
            type="button"
            disabled={forceComplete.isPending}
            onClick={() => {
              const trimmed = reason.trim();
              if (!trimmed) {
                setReasonError(t("customer.form.required"));
                return;
              }
              forceComplete.mutate({ caseId, milestoneId, reason: trimmed }, { onSuccess: onClose });
            }}
          >
            {t("milestone.forceCompleteDialog.submit")}
          </Button>
        </DialogActions>
      </div>
    </Dialog>
  );
}
