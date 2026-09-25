"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Checkbox } from "@/components/ui/Checkbox";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { TextareaField } from "@/components/ui/Field";
import { humanise } from "@/components/ui/StatusPill";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { useCreateDocumentRequest, type DocumentCategory } from "@/lib/api/documents";
import { t } from "@/lib/i18n";

/** Mirrors `document_category_ck` (backend `DocumentCategory.java`) exactly -- same list `UploadDialog` already carries, duplicated rather than shared per this codebase's own precedent for that file. */
const CATEGORIES: DocumentCategory[] = [
  "CONTRACT",
  "AGREEMENT",
  "NDA",
  "COMPANY_REGISTRATION",
  "TAX",
  "KYC",
  "TECHNICAL",
  "CERTIFICATE",
  "INVOICE",
  "OTHER",
];

const FIELD_LABEL_STYLE = { fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 } as const;

const SELECT_STYLE = {
  border: "1px solid var(--ob-line)",
  borderRadius: "var(--ob-radius-9)",
  padding: "9px 11px",
  fontSize: "13px",
  background: "var(--ob-surface)",
  color: "var(--ob-ink)",
  fontFamily: "var(--ob-font-family-ui)",
} as const;

/**
 * The case header's `Request document` primary action (Task 34, `SCREENS.md`
 * §3). Consumes `useCreateDocumentRequest` -- an ad-hoc request against a
 * case, `requirementId` always null on this path (that hook's own doc
 * comment).
 *
 * Only `category`/`description`/`requiresReview` are collected here, the same
 * narrow-first-pass scoping `UploadDialog` already established: `dueAt` and
 * `requestedOfContactId` are both nullable and no date/contact picker exists
 * anywhere in this codebase yet to drive either, so omitting them is a real
 * "not built yet", not a schema mismatch.
 *
 * `requiresReview` is the field the brief's own Step 1 test names directly:
 * a real, testable control backs it, and its current value -- never a
 * hardcoded `false` -- is what reaches the submitted request body.
 */
export function RequestDocumentDialog({ caseId, onClose }: { caseId: string; onClose: () => void }) {
  const createRequest = useCreateDocumentRequest();
  const categoryId = useId();

  const [category, setCategory] = useState<DocumentCategory>("OTHER");
  const [description, setDescription] = useState("");
  const [requiresReview, setRequiresReview] = useState(false);

  function submit() {
    const trimmedDescription = description.trim();
    createRequest.mutate(
      {
        caseId,
        category,
        description: trimmedDescription || undefined,
        requiresReview,
      },
      { onSuccess: onClose },
    );
  }

  return (
    <Dialog title={t("documents.request.title")} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <div className="flex flex-col">
          <label htmlFor={categoryId} style={FIELD_LABEL_STYLE}>
            {t("documents.request.category")}
          </label>
          <select
            id={categoryId}
            value={category}
            onChange={(event) => setCategory(event.target.value as DocumentCategory)}
            style={SELECT_STYLE}
          >
            {CATEGORIES.map((option) => (
              <option key={option} value={option}>
                {humanise(option)}
              </option>
            ))}
          </select>
        </div>

        <TextareaField
          label={t("documents.request.description")}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
        />

        <Checkbox
          checked={requiresReview}
          onChange={setRequiresReview}
          label={t("documents.request.requiresReview")}
        />

        {createRequest.isError && (
          <p
            role="alert"
            style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {createRequest.error instanceof ApiError ? parseProblemDetail(createRequest.error.message) : t("common.error")}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={createRequest.isPending} onClick={submit}>
          {t("documents.request.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
