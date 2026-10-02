"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import {
  useReviewAgreement,
  useSendAgreement,
  useSubmitAgreement,
  type AgreementDetail,
} from "@/lib/api/agreements";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useAuth } from "@/lib/auth/useAuth";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";
import { CancelAgreementDialog } from "./CancelAgreementDialog";
import { RecordSignatureDialog } from "./RecordSignatureDialog";
import { ReviewAgreementDialog } from "./ReviewAgreementDialog";

type Dialogs = "reject" | "record" | "cancel" | null;

/**
 * The lifecycle actions for the agreement's current status. SIGNED, EXPIRED and CANCELLED have none.
 *
 * Four-eyes: Approve and Reject are shown but disabled, with the reason, when the viewer submitted or
 * last edited the latest version, so the rule is visible rather than a missing button. The server
 * (AgreementReviewService) is the guard; a refusal comes back as a 409 whose detail is shown verbatim.
 * Every write carries the agreement's CURRENT lockVersion explicitly -- it is optional in the
 * generated types, but an omitted value binds to 0 and would 409 spuriously.
 */
export function AgreementActions({
  detail,
  onOpenAgreement,
  onStale,
}: {
  detail: AgreementDetail;
  onOpenAgreement: (id: string) => void;
  onStale: () => void;
}) {
  const { user } = useAuth();
  const canManage = useHasPermission("agreement.manage");
  const canReview = useHasPermission("agreement.review");
  const canRecord = useHasPermission("agreement.sign_record");
  const submit = useSubmitAgreement();
  const review = useReviewAgreement();
  const send = useSendAgreement();
  const [dialog, setDialog] = useState<Dialogs>(null);
  const [error, setError] = useState<string>();
  const noteId = useId();

  const agreement = detail.agreement;
  if (!agreement?.id) return null;
  const id = agreement.id;
  const status = agreement.status;
  const lockVersion = agreement.lockVersion ?? 0;
  // A displayStatus of EXPIRED is a SIGNED agreement past its expiry; it has no actions either.
  const live = agreement.displayStatus !== "EXPIRED";

  const versions = detail.versions ?? [];
  const latestNumber = agreement.latestVersionNumber ?? Math.max(0, ...versions.map((v) => v.versionNumber ?? 0));
  const latest = versions.find((v) => v.versionNumber === latestNumber);
  const me = user?.id;
  const selfNote =
    me && latest?.submittedBy === me
      ? t("agreements.actions.selfSubmitted")
      : me && latest?.lastEditedBy === me
        ? t("agreements.actions.selfEdited")
        : undefined;

  function fail(err: unknown) {
    setError(err instanceof ApiError ? parseProblemDetail(err.message) : t("common.error"));
    if (err instanceof ApiError && err.status === 409) onStale();
  }
  const run = { onMutate: () => setError(undefined), onError: fail };

  const canCancel = canManage && live && status !== "SIGNED" && status !== "CANCELLED";
  const canSubmit = canManage && status === "DRAFT";
  const canSend = canManage && status === "APPROVED";
  const showReview = canReview && status === "UNDER_REVIEW";
  const showRecord = canRecord && (status === "SENT" || status === "AWAITING_SIGNATURE");

  if (!canSubmit && !canSend && !showReview && !showRecord && !canCancel) return null;

  const busy = submit.isPending || review.isPending || send.isPending;

  return (
    <div className="flex flex-col" role="group" aria-label={t("agreements.actions.label")} style={{ gap: "var(--ob-space-8)" }}>
      <div className="flex flex-wrap items-center" style={{ gap: "var(--ob-space-8)" }}>
        {canSubmit && (
          <Button type="button" disabled={busy} onClick={() => submit.mutate({ id, lockVersion }, run)}>
            {t("agreements.actions.submit")}
          </Button>
        )}
        {showReview && (
          <>
            <Button
              type="button"
              disabled={busy || Boolean(selfNote)}
              aria-describedby={selfNote ? noteId : undefined}
              onClick={() => review.mutate({ id, versionNumber: latestNumber, body: { decision: "APPROVE", lockVersion } }, run)}
            >
              {t("agreements.actions.approve")}
            </Button>
            <Button
              type="button"
              variant="secondary"
              disabled={busy || Boolean(selfNote)}
              aria-describedby={selfNote ? noteId : undefined}
              onClick={() => setDialog("reject")}
            >
              {t("agreements.actions.reject")}
            </Button>
          </>
        )}
        {canSend && (
          <Button type="button" disabled={busy} onClick={() => send.mutate({ id, lockVersion }, run)}>
            {t("agreements.actions.send")}
          </Button>
        )}
        {showRecord && (
          <Button type="button" onClick={() => setDialog("record")}>
            {t("agreements.actions.record")}
          </Button>
        )}
        {canCancel && (
          <Button type="button" variant="danger-outline" onClick={() => setDialog("cancel")}>
            {t("agreements.actions.cancel")}
          </Button>
        )}
      </div>

      {showReview && selfNote && (
        <p id={noteId} className="text-text-subtle" style={{ fontSize: "12.5px" }}>
          {selfNote}
        </p>
      )}

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            background: "var(--ob-risk-bg)",
            border: "1px solid var(--ob-risk-border)",
            borderRadius: "var(--ob-radius-9)",
            padding: "var(--ob-space-8) var(--ob-space-11)",
            fontSize: "12.5px",
          }}
        >
          {error}
        </p>
      )}

      {dialog === "reject" && (
        <ReviewAgreementDialog agreementId={id} versionNumber={latestNumber} lockVersion={lockVersion} onClose={() => setDialog(null)} onStale={onStale} />
      )}
      {dialog === "record" && (
        <RecordSignatureDialog agreement={agreement} signatories={detail.signatories ?? []} onClose={() => setDialog(null)} onStale={onStale} />
      )}
      {dialog === "cancel" && (
        <CancelAgreementDialog
          agreementId={id}
          lockVersion={lockVersion}
          onClose={() => setDialog(null)}
          onStale={onStale}
          onCancelled={(successorId) => {
            setDialog(null);
            if (successorId) onOpenAgreement(successorId);
          }}
        />
      )}
    </div>
  );
}
