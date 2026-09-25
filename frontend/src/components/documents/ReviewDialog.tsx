"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { useReviewVersion, type ReviewDecision } from "@/lib/api/documents";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * A pending version's review decision (Task 34). Consumes `useReviewVersion`.
 *
 * **Ruling 3 (task-34-brief.md): no live entry point in this task.** No
 * screen in the codebase can yet answer "which version is pending review" --
 * `DocumentView` carries no review-status field at all (it lives on the more
 * granular `DocumentVersion`, returned only from specific mutations), and the
 * one backend method that could answer it (`DocumentReviewService.pending()`)
 * has no HTTP endpoint yet. This component is deliberately a clean,
 * prop-driven dialog (`documentId`/`versionNo`/`onClose`) with no page
 * mounting it -- exactly like `ScopeFilterRow`/`HiddenCountLine` (Task 32)
 * before their own callers existed. The future caller that gets real
 * pending-review data renders this directly; nothing here needs to change.
 *
 * Rejecting requires a note (a client-side UX guard tighter than the
 * backend's own rule): `ReviewVersionRequest.note` is deliberately NOT
 * `@NotBlank` on the server (its own doc comment: a decision is already fully
 * recorded by `decision`/`reviewedBy`/`reviewedAt`, and "approved, no
 * comment" is a legitimate reviewer action) -- but a REJECTED decision with no
 * explanation leaves the requester with nothing actionable, so the submit
 * control stays disabled until a note is present, purely in the UI.
 */
export function ReviewDialog({
  documentId,
  versionNo,
  onClose,
}: {
  documentId: string;
  versionNo: number;
  onClose: () => void;
}) {
  const reviewVersion = useReviewVersion();
  const groupName = useId();

  const [decision, setDecision] = useState<ReviewDecision>("APPROVED");
  const [note, setNote] = useState("");

  const trimmedNote = note.trim();
  const rejectingWithNoNote = decision === "REJECTED" && !trimmedNote;

  function submit() {
    if (rejectingWithNoNote) return;
    reviewVersion.mutate(
      { documentId, versionNo, decision, note: trimmedNote || undefined },
      { onSuccess: onClose },
    );
  }

  return (
    <Dialog title={t("documents.review.title", { version: String(versionNo) })} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <div className="flex" style={{ gap: "var(--ob-space-16)" }}>
          <label className="inline-flex items-center" style={{ gap: "var(--ob-space-6)" }}>
            <input
              type="radio"
              name={groupName}
              value="APPROVED"
              checked={decision === "APPROVED"}
              onChange={() => setDecision("APPROVED")}
            />
            {t("documents.review.decision.approve")}
          </label>
          <label className="inline-flex items-center" style={{ gap: "var(--ob-space-6)" }}>
            <input
              type="radio"
              name={groupName}
              value="REJECTED"
              checked={decision === "REJECTED"}
              onChange={() => setDecision("REJECTED")}
            />
            {t("documents.review.decision.reject")}
          </label>
        </div>

        <TextareaField
          label={t("documents.review.note")}
          value={note}
          error={rejectingWithNoNote ? t("documents.review.note.rejectRequired") : undefined}
          onChange={(event) => setNote(event.target.value)}
        />

        {reviewVersion.isError && (
          <p
            role="alert"
            style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {reviewVersion.error instanceof ApiError ? parseProblemDetail(reviewVersion.error.message) : t("common.error")}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={reviewVersion.isPending || rejectingWithNoNote} onClick={submit}>
          {t("documents.review.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * The permission-gated trigger a future caller mounts to open `ReviewDialog`
 * (Ruling 3's own guidance: gate whatever small wrapper opens the dialog,
 * since the dialog itself has no live entry point to gate directly). Mirrors
 * `page.tsx`'s own `useHasPermission("case.hold")` precedent exactly -- a
 * caller without `document.review` gets no button at all, not a disabled one.
 */
export function ReviewAffordance({ documentId, versionNo }: { documentId: string; versionNo: number }) {
  const canReview = useHasPermission("document.review");
  const [open, setOpen] = useState(false);

  if (!canReview) return null;

  return (
    <>
      <Button type="button" variant="secondary" onClick={() => setOpen(true)}>
        {t("documents.review.action")}
      </Button>
      {open && <ReviewDialog documentId={documentId} versionNo={versionNo} onClose={() => setOpen(false)} />}
    </>
  );
}
