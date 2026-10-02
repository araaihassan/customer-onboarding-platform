"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { useReviewAgreement } from "@/lib/api/agreements";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/** Rejecting needs a reason (the server refuses a blank one); approving needs no dialog. */
export function ReviewAgreementDialog({
  agreementId,
  versionNumber,
  lockVersion,
  onClose,
  onStale,
}: {
  agreementId: string;
  versionNumber: number;
  lockVersion: number;
  onClose: () => void;
  onStale: () => void;
}) {
  const review = useReviewAgreement();
  const [reason, setReason] = useState("");
  const [reasonError, setReasonError] = useState<string>();

  function submit() {
    const trimmed = reason.trim();
    if (!trimmed) {
      setReasonError(t("customer.form.required"));
      return;
    }
    review.mutate(
      { id: agreementId, versionNumber, body: { decision: "REJECT", reason: trimmed, lockVersion } },
      {
        onSuccess: onClose,
        onError: (err) => {
          if (err instanceof ApiError && err.status === 409) onStale();
        },
      },
    );
  }

  return (
    <Dialog title={t("agreements.reject.title")} onClose={onClose}>
      <p className="text-text-subtle" style={{ fontSize: "12.5px", marginBottom: "var(--ob-space-11)" }}>{t("agreements.reject.intro")}</p>
      <TextareaField
        label={t("agreements.reject.reason")}
        value={reason}
        error={reasonError}
        onChange={(e) => {
          setReason(e.target.value);
          setReasonError(undefined);
        }}
      />
      {review.isError && (
        <p role="alert" style={{ color: "var(--ob-risk-fg)", marginTop: "var(--ob-space-11)", fontSize: "12.5px" }}>
          {review.error instanceof ApiError ? parseProblemDetail(review.error.message) : t("common.error")}
        </p>
      )}
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button>
        <Button type="button" disabled={review.isPending} onClick={submit}>{t("agreements.reject.submit")}</Button>
      </DialogActions>
    </Dialog>
  );
}
