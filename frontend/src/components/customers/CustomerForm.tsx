"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import type { Customer } from "@/lib/api/customers";
import { t } from "@/lib/i18n";

/**
 * The four fields a customer can be created or edited with.
 *
 * Deliberately not the whole of `UpdateCustomerRequest`. The three ownership ids
 * back the DEPARTMENT, TEAM and ASSIGNED scope predicates, and `externalRef` is
 * the customer's identifier in whatever system it came from; none has a control
 * in sub-project 1, so the caller round-trips all four from the record it is
 * editing — see the detail page.
 *
 * Omitting them is not a safe alternative. PUT replaces every field, so a field
 * left out of the body is blanked exactly as a field sent empty would be.
 */
export type CustomerFormValues = {
  displayName: string;
  legalName: string;
  industry: string;
  country: string;
};

type Errors = Partial<Record<keyof CustomerFormValues, string>>;

export function CustomerForm({
  initial,
  submitLabel,
  pending = false,
  error,
  onSubmit,
  onCancel,
}: {
  initial?: Customer;
  submitLabel: string;
  pending?: boolean;
  /** A failed save, reported without discarding what was typed. */
  error?: string;
  onSubmit: (values: CustomerFormValues) => void;
  onCancel: () => void;
}) {
  const [values, setValues] = useState<CustomerFormValues>({
    displayName: initial?.displayName ?? "",
    legalName: initial?.legalName ?? "",
    industry: initial?.industry ?? "",
    country: initial?.country ?? "",
  });
  const [errors, setErrors] = useState<Errors>({});

  function set(field: keyof CustomerFormValues, value: string) {
    setValues((previous) => ({ ...previous, [field]: value }));
    // Cleared on edit rather than on the next submit: an error message that
    // outlives the problem trains people to ignore error messages.
    setErrors((previous) => (previous[field] ? { ...previous, [field]: undefined } : previous));
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();

    const trimmed: CustomerFormValues = {
      displayName: values.displayName.trim(),
      legalName: values.legalName.trim(),
      industry: values.industry.trim(),
      // ISO 3166-1 alpha-2, and the column is two characters. Normalising here
      // is what stops "gb" and "GB" becoming two countries in every report that
      // ever groups by it.
      country: values.country.trim().toUpperCase(),
    };

    const next: Errors = {};
    if (!trimmed.displayName) next.displayName = t("customer.form.required");
    if (!trimmed.legalName) next.legalName = t("customer.form.required");
    if (Object.keys(next).length > 0) {
      setErrors(next);
      return;
    }

    onSubmit(trimmed);
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("customer.form.displayName")}
          value={values.displayName}
          error={errors.displayName}
          onChange={(event) => set("displayName", event.target.value)}
        />
        <Field
          label={t("customer.form.legalName")}
          value={values.legalName}
          error={errors.legalName}
          onChange={(event) => set("legalName", event.target.value)}
        />
        <Field
          label={t("customer.form.industry")}
          value={values.industry}
          onChange={(event) => set("industry", event.target.value)}
        />
        <Field
          label={t("customer.form.country")}
          value={values.country}
          maxLength={2}
          placeholder="GB"
          onChange={(event) => set("country", event.target.value)}
        />
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-status-blocked-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-11-5-size)/var(--ob-type-11-5-line) var(--ob-font-family-ui)",
          }}
        >
          {error}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onCancel}>
          {t("common.cancel")}
        </Button>
        <Button type="submit" disabled={pending}>
          {submitLabel}
        </Button>
      </DialogActions>
    </form>
  );
}
