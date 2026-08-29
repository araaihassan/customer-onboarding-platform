"use client";

import { useId, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import { useCreateCase } from "@/lib/api/cases";
import { parseProblems, useDefinition, useWorkflows, type Attribute } from "@/lib/api/workflows";
import { t } from "@/lib/i18n";

/**
 * The switcher's dashed "+ New case" chip opens this (uispecs README §5). Only
 * templates with a published version are offered, because CaseService.create
 * refuses the rest with a 409 the dialog would otherwise have to explain after
 * the fact.
 *
 * Every declared attribute on the chosen template's current version renders
 * its own field -- required ones marked, a closed list rendered as a select --
 * and a 422's problems, all shaped "Attribute '{key}' ...", are matched back
 * to the field they name rather than dumped as one undifferentiated list.
 *
 * The case's own name (Q18) is collected here, required client-side exactly
 * as HoldDialog's reason and admin/users' fullName/email are: trimmed and
 * checked on submit, not disabling the button while empty. There is no
 * fallback for an empty name anymore -- CaseService.create rejects a blank
 * one (@NotBlank), so this is the only place a name is ever supplied.
 * `maxLength={160}` matches `onboarding_case.name`'s own `varchar(160)`
 * (fix round 2) -- CreateCaseRequest.name carries the actual `@Size(max =
 * 160)` enforcement; this is a client-side courtesy so the field can't even
 * be typed past the limit, not the guard itself.
 */
export function CreateCaseDialog({
  customerId,
  onCreated,
  onCancel,
}: {
  customerId: string;
  onCreated: (caseId: string) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState("");
  const [nameError, setNameError] = useState<string>();
  const [templateId, setTemplateId] = useState("");
  const [values, setValues] = useState<Record<string, string>>({});
  const [problems, setProblems] = useState<string[]>([]);

  const workflows = useWorkflows();
  const templates = (workflows.data ?? []).filter((tpl) => tpl.currentVersionId);
  const selectedTemplate = templates.find((tpl) => tpl.id === templateId);
  const definition = useDefinition(templateId, selectedTemplate?.currentVersionId ?? "");
  const createCase = useCreateCase();

  const attributes = definition.data?.attributes ?? [];

  function selectTemplate(id: string) {
    setTemplateId(id);
    setValues({});
    setProblems([]);
  }

  function problemFor(key: string): string | undefined {
    return problems.find((p) => p.includes(`'${key}'`));
  }
  const unmatchedProblems = problems.filter(
    (p) => !attributes.some((a) => a.key && p.includes(`'${a.key}'`)),
  );

  function submit() {
    const trimmedName = name.trim();
    if (!trimmedName) {
      setNameError(t("customer.form.required"));
      return;
    }
    if (!templateId) return;
    setProblems([]);
    createCase.mutate(
      { customerId, templateId, name: trimmedName, attributes: values },
      {
        onSuccess: (created) => {
          if (created.id) onCreated(created.id);
        },
        onError: (error) => {
          if (error instanceof ApiError && error.status === 422) {
            setProblems(parseProblems(error.message));
          }
        },
      },
    );
  }

  return (
    <Dialog title={t("case.create.title")} onClose={onCancel}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("case.create.name")}
          placeholder={t("case.create.namePlaceholder")}
          value={name}
          error={nameError}
          maxLength={160}
          onChange={(e) => {
            setName(e.target.value);
            setNameError(undefined);
          }}
        />

        <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
          <span
            className="text-text-muted"
            style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
          >
            {t("case.create.template")}
          </span>
          <div role="radiogroup" aria-label={t("case.create.template")} className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
            {templates.map((tpl) => (
              <button
                key={tpl.id}
                type="button"
                role="radio"
                aria-checked={templateId === tpl.id}
                onClick={() => selectTemplate(tpl.id ?? "")}
                className="text-left"
                style={{
                  height: "var(--ob-control-height)",
                  padding: "0 var(--ob-space-13)",
                  borderRadius: "var(--ob-radius-8)",
                  border: `1px solid var(${templateId === tpl.id ? "--ob-accent-fg" : "--ob-line"})`,
                  background: templateId === tpl.id ? "var(--ob-accent-bg)" : "var(--ob-surface)",
                  color: "var(--ob-ink)",
                  font: "500 var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)",
                  cursor: "pointer",
                }}
              >
                {tpl.name}
              </button>
            ))}
          </div>
        </div>

        {templateId && definition.isLoading && <SkeletonRows rows={2} height={40} />}

        {templateId &&
          !definition.isLoading &&
          attributes.map((attribute) => (
            <div key={attribute.key} data-testid={`attribute-field-${attribute.key}`}>
              <AttributeField
                attribute={attribute}
                value={values[attribute.key ?? ""] ?? ""}
                onChange={(value) => setValues((prev) => ({ ...prev, [attribute.key ?? ""]: value }))}
              />
              {attribute.key && problemFor(attribute.key) && (
                <p role="alert" style={errorStyle}>
                  {problemFor(attribute.key)}
                </p>
              )}
            </div>
          ))}

        {unmatchedProblems.length > 0 && (
          <ul style={{ display: "flex", flexDirection: "column", gap: "var(--ob-space-4)" }}>
            {unmatchedProblems.map((problem, i) => (
              <li key={i} role="alert" style={errorStyle}>
                {problem}
              </li>
            ))}
          </ul>
        )}
      </div>

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={!templateId || createCase.isPending} onClick={submit}>
          {t("case.create.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

function AttributeField({
  attribute,
  value,
  onChange,
}: {
  attribute: Attribute;
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const label = `${attribute.label ?? attribute.key}${attribute.required ? " *" : ""}`;

  if (attribute.allowedValues && attribute.allowedValues.length > 0) {
    return (
      <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
        <label
          htmlFor={id}
          className="text-text-muted"
          style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {label}
        </label>
        <select id={id} value={value} onChange={(e) => onChange(e.target.value)} style={selectStyle}>
          <option value="" disabled>
            {t("common.select")}
          </option>
          {attribute.allowedValues.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </select>
      </div>
    );
  }

  if (attribute.dataType === "BOOLEAN") {
    return (
      <label className="inline-flex items-center" style={{ gap: "var(--ob-space-8)" }}>
        <input
          id={id}
          type="checkbox"
          checked={value === "true"}
          onChange={(e) => onChange(e.target.checked ? "true" : "false")}
        />
        <span className="text-text-muted" style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}>
          {label}
        </span>
      </label>
    );
  }

  return (
    <Field
      label={label}
      type={attribute.dataType === "NUMBER" ? "number" : attribute.dataType === "DATE" ? "date" : "text"}
      value={value}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

const selectStyle = {
  height: "var(--ob-control-height)",
  borderRadius: "var(--ob-radius-9)",
  border: "1px solid var(--ob-line)",
  background: "var(--ob-surface)",
  padding: "0 var(--ob-space-11)",
  font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)",
} as const;

const errorStyle = {
  color: "var(--ob-risk-fg)",
  marginTop: "var(--ob-space-4)",
  font: "var(--ob-type-small-print-size)/var(--ob-type-small-print-line) var(--ob-font-family-ui)",
} as const;
