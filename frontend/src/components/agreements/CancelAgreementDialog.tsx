"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { useCancelAgreement } from "@/lib/api/agreements";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/** Cancelling returns the successor draft; the caller opens it. */
export function CancelAgreementDialog({
  agreementId,
  lockVersion,
  onClose,
  onCancelled,
  onStale,
}: {
  agreementId: string;
  lockVersion: number;
  onClose: () => void;
  onCancelled: (successorId: string | undefined) => void;
  onStale: () => void;
}) {
  const cancel = useCancelAgreement();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  function submit() {
    const trimmed = reason.trim();
    if (!trimmed) {
      setReasonError(t("customer.form.required"));
      return;
    }
    cancel.mutate(
      { id: agreementId, reason: trimmed, lockVersion },
      {
        onSuccess: (detail) => onCancelled(detail.agreement?.id),
        onError: (err) => {
          if (err instanceof ApiError && err.status === 409) onStale();
        },
      },
    );
  }

  return (
    <Dialog title={t("agreements.cancel.title")} onClose={onClose}>
      <p className="text-text-subtle" style={{ fontSize: "12.5px", marginBottom: "var(--ob-space-11)" }}>{t("agreements.cancel.intro")}</p>
      <TextareaField
        label={t("agreements.cancel.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />
      {cancel.isError && (
        <p role="alert" style={{ color: "var(--ob-risk-fg)", marginTop: "var(--ob-space-11)", fontSize: "12.5px" }}>
          {cancel.error instanceof ApiError ? parseProblemDetail(cancel.error.message) : t("common.error")}
        </p>
      )}
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>{t("common.close")}</Button>
        <Button type="button" variant="danger-outline" disabled={cancel.isPending} onClick={submit}>{t("agreements.cancel.submit")}</Button>
      </DialogActions>
    </Dialog>
  );
}
