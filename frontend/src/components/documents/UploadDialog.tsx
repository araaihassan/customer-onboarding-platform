"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { humanise } from "@/components/ui/StatusPill";
import { parseProblemDetail } from "@/lib/api/cases";
import { ApiError } from "@/lib/api/client";
import { DOCUMENT_CATEGORIES as CATEGORIES, useUploadDocument, type Document, type DocumentCategory, type DocumentVisibilityTier } from "@/lib/api/documents";
import { t } from "@/lib/i18n";
import { VisibilityAside } from "./VisibilityAside";

/** Mirrors `document_tier_ck` (backend `VisibilityTier.java`) exactly -- the brief's own Step 1 test asserts the select offers precisely these three, in this order. */
const TIERS: DocumentVisibilityTier[] = ["COMPANY_SHARED", "CONTACT_ONLY", "SENSITIVE"];

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
 * The case workspace Documents tab's upload flow (Task 33). Consumes
 * `useUploadDocument` -- the same two-part multipart contract (`file` +
 * JSON `metadata`) that hook's own doc comment already documents.
 *
 * Only `name`/`category`/`visibilityTier` are collected here.
 * `CreateDocumentRequest`'s other fields (`targetDepartmentId`,
 * `targetContactLabel`, `ownerContactId`, `expiresAt`) are all nullable and
 * no picker for any of them exists anywhere in the codebase yet -- omitting
 * them is a real "not built yet", not a schema mismatch (the request type
 * accepts every one of them as absent).
 *
 * **Ruling 2.** `VisibilityAside` is mounted here, beside the visibility
 * select, with the CURRENTLY SELECTED tier as its emphasised row -- see that
 * component's own doc comment for why this, not a page-level rail slot, is
 * where `SCREENS.md` §7's "How visibility works" belongs.
 *
 * `defaultName`/`defaultCategory`/`onUploaded` exist for `DocumentChip`
 * (`RequirementList.tsx`): uploading to satisfy a specific open document
 * request pre-fills the name/category the request itself already carries
 * (its own `description`/`category`, not a guess), and `onUploaded` lets the
 * caller chain a `fulfil` call onto the newly created document once the
 * upload mutation actually succeeds -- the Documents tab's own plain
 * `UploadDialog caseId={caseId}` call site needs neither and gets the
 * pre-existing defaults (blank name, `OTHER`, no callback).
 */
export function UploadDialog({
  caseId,
  onClose,
  onUploaded,
  defaultName = "",
  defaultCategory = "OTHER",
}: {
  caseId: string;
  onClose: () => void;
  onUploaded?: (document: Document) => void;
  defaultName?: string;
  defaultCategory?: DocumentCategory;
}) {
  const uploadDocument = useUploadDocument();
  const categoryId = useId();
  const visibilityId = useId();
  const fileId = useId();

  const [name, setName] = useState(defaultName);
  const [category, setCategory] = useState<DocumentCategory>(defaultCategory);
  const [visibilityTier, setVisibilityTier] = useState<DocumentVisibilityTier>("COMPANY_SHARED");
  const [file, setFile] = useState<File>();
  const [nameError, setNameError] = useState<string>();
  const [fileError, setFileError] = useState<string>();

  function submit() {
    const trimmedName = name.trim();
    const missingName = !trimmedName;
    const missingFile = !file;

    if (missingName) setNameError(t("customer.form.required"));
    if (missingFile) setFileError(t("customer.form.required"));
    if (missingName || missingFile || !file) return;

    uploadDocument.mutate(
      { caseId, file, metadata: { name: trimmedName, category, visibilityTier } },
      {
        onSuccess: (created) => {
          onUploaded?.(created);
          onClose();
        },
      },
    );
  }

  return (
    <Dialog title={t("documents.upload.title")} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <Field
          label={t("documents.upload.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />

        <div className="flex flex-col">
          <label htmlFor={fileId} style={FIELD_LABEL_STYLE}>
            {t("documents.upload.file")}
          </label>
          <input
            id={fileId}
            type="file"
            aria-invalid={fileError ? true : undefined}
            onChange={(event) => {
              setFile(event.target.files?.[0]);
              setFileError(undefined);
            }}
            style={{ fontSize: "13px", fontFamily: "var(--ob-font-family-ui)" }}
          />
          {fileError && (
            <p role="alert" style={{ color: "var(--ob-risk-fg)", fontSize: "11.5px", fontFamily: "var(--ob-font-family-ui)" }}>
              {fileError}
            </p>
          )}
        </div>

        <div className="flex flex-col">
          <label htmlFor={categoryId} style={FIELD_LABEL_STYLE}>
            {t("documents.upload.category")}
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

        <div className="flex flex-col">
          <label htmlFor={visibilityId} style={FIELD_LABEL_STYLE}>
            {t("documents.upload.visibility")}
          </label>
          <select
            id={visibilityId}
            value={visibilityTier}
            onChange={(event) => setVisibilityTier(event.target.value as DocumentVisibilityTier)}
            style={SELECT_STYLE}
          >
            {TIERS.map((tier) => (
              <option key={tier} value={tier}>
                {humanise(tier)}
              </option>
            ))}
          </select>
        </div>

        <VisibilityAside emphasize={visibilityTier} />

        {uploadDocument.isError && (
          <p
            role="alert"
            style={{ color: "var(--ob-risk-fg)", font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {uploadDocument.error instanceof ApiError ? parseProblemDetail(uploadDocument.error.message) : t("common.error")}
          </p>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        {/* Waits for the mutation rather than optimistically flipping --
            `RequirementList`'s checkbox is this codebase's own precedent
            (CLAUDE.md names it "real and local" departure): the control
            stays enabled until a real submit attempt starts a real request,
            then disables for the duration of `isPending`, exactly like that
            checkbox disables for the duration of `satisfy.isPending`. */}
        <Button type="button" disabled={uploadDocument.isPending} onClick={submit}>
          {t("documents.upload.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
