"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail, useHold } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/** `hold.reason` is `NOT NULL` (spec §5.1), the same shape as force-complete's. */
export function HoldDialog({ caseId, onClose }: { caseId: string; onClose: () => void }) {
  const hold = useHold();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  return (
    <Dialog title={t("case.holdDialog.title")} onClose={onClose}>
      <TextareaField
        label={t("case.holdDialog.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />

      {hold.isError && (
        <p
          role="alert"
          style={{ color: "var(--ob-status-blocked-fg)", marginTop: "var(--ob-space-11)", font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)" }}
        >
          {hold.error instanceof ApiError ? parseProblemDetail(hold.error.message) : t("common.error")}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button
          type="button"
          disabled={hold.isPending}
          onClick={() => {
            const trimmed = reason.trim();
            if (!trimmed) {
              setReasonError(t("customer.form.required"));
              return;
            }
            hold.mutate({ id: caseId, reason: trimmed }, { onSuccess: onClose });
          }}
        >
          {t("case.holdDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
