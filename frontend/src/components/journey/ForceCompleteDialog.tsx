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
    <Dialog title={t("milestone.forceCompleteDialog.title")} onClose={onClose}>
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
          style={{ color: "var(--ob-status-blocked-fg)", marginTop: "var(--ob-space-11)", font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
        >
          {forceComplete.error instanceof ApiError ? parseProblemDetail(forceComplete.error.message) : t("common.error")}
        </p>
      )}

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
    </Dialog>
  );
}
