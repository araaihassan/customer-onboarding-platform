"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Field } from "@/components/ui/Field";
import {
  recordModeIncludesFile,
  usePatchAgreement,
  useUploadAgreementFile,
  type Agreement,
  type PatchAgreementRequest,
} from "@/lib/api/agreements";
import { t } from "@/lib/i18n";
import { formatAgreementDate } from "./AgreementRow";
import { OpenAgreementFile } from "./OpenAgreementFile";

type Clearable = NonNullable<PatchAgreementRequest["clear"]>[number];

const MONO = "var(--ob-font-family-data)";

/**
 * The agreement's own terms. Editable only while the panel says so (DRAFT and agreement.manage);
 * otherwise the same fields render as plain read-only values.
 *
 * PATCH contract (PatchAgreementRequest): a null field is unchanged, so only changed fields are sent,
 * and a field the user emptied goes in `clear` rather than as an empty string. `lockVersion` is
 * optional in the generated type but the backend binds an omitted value to 0 -- a spurious 409 -- so
 * it is passed explicitly on every write, the upload included.
 */
export function DraftEditor({
  agreement,
  editable,
  onError,
}: {
  agreement: Agreement;
  editable: boolean;
  onError: (err: unknown) => void;
}) {
  const patch = usePatchAgreement();
  const upload = useUploadAgreementFile();
  const [name, setName] = useState(agreement.name ?? "");
  const [effectiveDate, setEffectiveDate] = useState(agreement.effectiveDate ?? "");
  const [expiresAt, setExpiresAt] = useState(agreement.expiresAt ?? "");
  const [renewalDate, setRenewalDate] = useState(agreement.renewalDate ?? "");
  const [notice, setNotice] = useState(agreement.noticePeriodDays?.toString() ?? "");
  const [nameError, setNameError] = useState<string>();

  const lockVersion = agreement.lockVersion ?? 0;
  const showFile = recordModeIncludesFile(agreement.recordMode ?? "FILE_BACKED");

  if (!editable) {
    return (
      <dl className="grid" style={{ gridTemplateColumns: "max-content 1fr", gap: "var(--ob-space-8) var(--ob-space-16)" }}>
        <ReadRow label={t("agreements.field.name")} value={agreement.name} human />
        <ReadRow label={t("agreements.field.effectiveDate")} value={agreement.effectiveDate && formatAgreementDate(agreement.effectiveDate)} />
        <ReadRow label={t("agreements.field.expiresAt")} value={agreement.expiresAt && formatAgreementDate(agreement.expiresAt)} />
        <ReadRow label={t("agreements.field.renewalDate")} value={agreement.renewalDate && formatAgreementDate(agreement.renewalDate)} />
        <ReadRow label={t("agreements.field.noticePeriodDays")} value={agreement.noticePeriodDays?.toString()} />
      </dl>
    );
  }

  const body = (): PatchAgreementRequest => {
    const out: PatchAgreementRequest = { lockVersion };
    const clear: Clearable[] = [];
    if (name.trim() !== (agreement.name ?? "")) out.name = name.trim();
    const dates: [string, string | undefined, Clearable, "effectiveDate" | "expiresAt" | "renewalDate"][] = [
      [effectiveDate, agreement.effectiveDate, "EFFECTIVE_DATE", "effectiveDate"],
      [expiresAt, agreement.expiresAt, "EXPIRES_AT", "expiresAt"],
      [renewalDate, agreement.renewalDate, "RENEWAL_DATE", "renewalDate"],
    ];
    for (const [value, original, key, field] of dates) {
      if (value === (original ?? "")) continue;
      if (value === "") clear.push(key);
      else out[field] = value;
    }
    const originalNotice = agreement.noticePeriodDays?.toString() ?? "";
    if (notice !== originalNotice) {
      if (notice === "") clear.push("NOTICE_PERIOD_DAYS");
      else out.noticePeriodDays = Number(notice);
    }
    if (clear.length > 0) out.clear = clear;
    return out;
  };

  const dirty = Object.keys(body()).length > 1;

  function save() {
    if (!name.trim()) {
      setNameError(t("agreements.field.nameRequired"));
      return;
    }
    setNameError(undefined);
    if (!agreement.id) return;
    patch.mutate({ id: agreement.id, body: body() }, { onError });
  }

  function pickFile(file: File | undefined) {
    if (!file || !agreement.id) return;
    upload.mutate({ id: agreement.id, file, lockVersion }, { onError });
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      <Field label={t("agreements.field.name")} value={name} error={nameError} maxLength={200} onChange={(e) => setName(e.target.value)} />
      <div className="grid" style={{ gridTemplateColumns: "repeat(auto-fit, minmax(150px, 1fr))", gap: "var(--ob-space-11)" }}>
        <Field type="date" label={t("agreements.field.effectiveDate")} value={effectiveDate} style={{ fontFamily: MONO }} onChange={(e) => setEffectiveDate(e.target.value)} />
        <Field type="date" label={t("agreements.field.expiresAt")} value={expiresAt} style={{ fontFamily: MONO }} onChange={(e) => setExpiresAt(e.target.value)} />
        <Field type="date" label={t("agreements.field.renewalDate")} value={renewalDate} style={{ fontFamily: MONO }} onChange={(e) => setRenewalDate(e.target.value)} />
        <Field
          type="number"
          min={0}
          step={1}
          label={t("agreements.field.noticePeriodDays")}
          value={notice}
          style={{ fontFamily: MONO }}
          onChange={(e) => setNotice(e.target.value)}
        />
      </div>
      <div>
        <Button type="button" disabled={!dirty || patch.isPending} onClick={save}>
          {patch.isPending ? t("agreements.detail.saving") : t("agreements.detail.save")}
        </Button>
      </div>

      {showFile && (
        <div className="flex flex-col" style={{ gap: "var(--ob-space-4)" }}>
          <h4 className="text-ink" style={{ fontWeight: 600, fontSize: "13px" }}>{t("agreements.detail.section.file")}</h4>
          <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
            <p className="text-text-subtle" style={{ fontSize: "12.5px" }}>
              {agreement.documentId ? t("agreements.file.attached") : t("agreements.file.none")}
            </p>
            {agreement.documentId && <OpenAgreementFile documentId={agreement.documentId} />}
          </div>
          <Field
            type="file"
            label={agreement.documentId ? t("agreements.file.replace") : t("agreements.file.upload")}
            disabled={upload.isPending}
            onChange={(e) => {
              pickFile(e.target.files?.[0]);
              e.target.value = "";
            }}
          />
        </div>
      )}
    </div>
  );
}

function ReadRow({ label, value, human }: { label: string; value?: string | null; human?: boolean }) {
  return (
    <>
      <dt className="text-text-subtle" style={{ fontSize: "12px" }}>{label}</dt>
      <dd
        className={value ? "text-ink" : "text-text-faint"}
        style={{ fontSize: "13px", fontFamily: human ? "var(--ob-font-family-ui)" : MONO }}
      >
        {value || t("agreements.field.notSet")}
      </dd>
    </>
  );
}
