"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { recordModeIncludesFile, useRecordSignature, type Agreement, type AgreementSignatory } from "@/lib/api/agreements";
import { ApiError } from "@/lib/api/client";
import { parseProblemDetail } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/** Today's date in the viewer's own calendar, as an ISO yyyy-MM-dd string (never via toISOString, which is UTC). */
function localToday(): string {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

/**
 * Records a signature collected outside the platform. Lists only the unsigned signatories; when the
 * chosen one is the last, and the record mode includes a file, the countersigned file is required
 * (the server enforces the same rule). `lockVersion` rides in the JSON part explicitly.
 */
export function RecordSignatureDialog({
  agreement,
  signatories,
  onClose,
  onStale,
}: {
  agreement: Agreement;
  signatories: AgreementSignatory[];
  onClose: () => void;
  onStale: () => void;
}) {
  const record = useRecordSignature();
  const listId = useId();
  const selectId = useId();
  const today = localToday();
  const unsigned = [...signatories].filter((s) => !s.signed).sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));
  const needsFile = unsigned.length === 1 && recordModeIncludesFile(agreement.recordMode ?? "FILE_BACKED");

  const [signatoryId, setSignatoryId] = useState(unsigned.length === 1 ? (unsigned[0]?.id ?? "") : "");
  const [signedOn, setSignedOn] = useState(today);
  const [method, setMethod] = useState("");
  const [file, setFile] = useState<File>();
  const [errors, setErrors] = useState<{ signatory?: string; date?: string; method?: string; file?: string }>({});

  function submit() {
    const next: typeof errors = {};
    if (!signatoryId) next.signatory = t("agreements.record.signatoryRequired");
    if (!signedOn) next.date = t("agreements.record.signedOnRequired");
    else if (signedOn > today) next.date = t("agreements.record.futureDate");
    if (!method.trim()) next.method = t("agreements.record.methodRequired");
    if (needsFile && !file) next.file = t("agreements.record.fileRequired");
    setErrors(next);
    if (Object.keys(next).length > 0) return;

    record.mutate(
      {
        id: agreement.id ?? "",
        signature: { signatoryId, signedOn, method: method.trim(), lockVersion: agreement.lockVersion ?? 0 },
        file: needsFile ? file : undefined,
      },
      {
        onSuccess: onClose,
        onError: (err) => {
          if (err instanceof ApiError && err.status === 409) onStale();
        },
      },
    );
  }

  return (
    <Dialog title={t("agreements.record.title")} onClose={onClose} maxWidth={500}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>{t("agreements.record.intro")}</p>

        <div className="flex flex-col">
          <label htmlFor={selectId} style={{ fontSize: "11.5px", color: "var(--ob-text-subtle)", marginBottom: "5px", fontWeight: 500 }}>
            {t("agreements.record.signatory")}
          </label>
          <select
            id={selectId}
            value={signatoryId}
            aria-invalid={errors.signatory ? true : undefined}
            onChange={(e) => {
              setSignatoryId(e.target.value);
              setErrors((p) => ({ ...p, signatory: undefined }));
            }}
            style={{
              border: `1px solid var(${errors.signatory ? "--ob-risk-fg" : "--ob-line"})`,
              borderRadius: "var(--ob-radius-9)",
              padding: "9px 11px",
              fontSize: "13px",
              background: "var(--ob-surface)",
              color: "var(--ob-ink)",
            }}
          >
            <option value="">{t("agreements.record.signatoryPick")}</option>
            {unsigned.map((s) => (
              <option key={s.id} value={s.id}>
                {s.displayName} ({s.displayRole})
              </option>
            ))}
          </select>
          {errors.signatory && <p style={{ color: "var(--ob-risk-fg)", fontSize: "11.5px" }}>{errors.signatory}</p>}
        </div>

        <Field
          label={t("agreements.record.signedOn")}
          type="date"
          max={today}
          value={signedOn}
          error={errors.date}
          onChange={(e) => {
            setSignedOn(e.target.value);
            setErrors((p) => ({ ...p, date: undefined }));
          }}
        />

        <Field
          label={t("agreements.record.method")}
          list={listId}
          value={method}
          error={errors.method}
          onChange={(e) => {
            setMethod(e.target.value);
            setErrors((p) => ({ ...p, method: undefined }));
          }}
        />
        <datalist id={listId}>
          <option value={t("agreements.method.suggestion.wetInk")} />
          <option value={t("agreements.method.suggestion.emailedPdf")} />
        </datalist>

        {needsFile && (
          <div className="flex flex-col" style={{ gap: "var(--ob-space-5)" }}>
            <Field
              label={t("agreements.record.file")}
              type="file"
              error={errors.file}
              onChange={(e) => {
                setFile(e.target.files?.[0]);
                setErrors((p) => ({ ...p, file: undefined }));
              }}
            />
            <p className="text-text-subtle" style={{ fontSize: "11.5px" }}>{t("agreements.record.fileHint")}</p>
          </div>
        )}

        {record.isError && (
          <p role="alert" style={{ color: "var(--ob-risk-fg)", fontSize: "12.5px" }}>
            {record.error instanceof ApiError ? parseProblemDetail(record.error.message) : t("common.error")}
          </p>
        )}
      </div>
      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>{t("common.cancel")}</Button>
        <Button type="button" disabled={record.isPending} onClick={submit}>{t("agreements.record.submit")}</Button>
      </DialogActions>
    </Dialog>
  );
}
