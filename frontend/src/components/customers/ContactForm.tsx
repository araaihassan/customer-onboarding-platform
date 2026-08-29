"use client";

import { useId, useState } from "react";
import { CheckIcon } from "@/components/icons";
import { Button } from "@/components/ui/Button";
import { DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import type { Contact, ContactStatus } from "@/lib/api/customers";
import { t } from "@/lib/i18n";

/**
 * The person being added to, or corrected on, a customer.
 *
 * Exactly `UpdateContactRequest`'s six fields — which are `CreateContactRequest`'s
 * five plus `status`. That equality is deliberate and worth stating: a `PUT` here
 * is a full replace, so a field this form did not carry would be blanked on every
 * save. Task 27 lost three ownership ids that way and Task R1 lost `externalRef`;
 * the check that stops it happening a third time is that `ContactView` returns
 * every field `UpdateContactRequest` accepts, so nothing has to be guessed.
 *
 * `userId` is not here because the write does not accept it — it stays null until
 * the portal invitation is accepted (spec 9.1, QA Q12) — and `customerId` is not
 * here because a contact does not move between customers.
 */
export type ContactFormValues = {
  fullName: string;
  email: string;
  title: string;
  phone: string;
  primaryContact: boolean;
  status: ContactStatus;
};

type Errors = Partial<Record<"fullName" | "email", string>>;

/** ACTIVE and INACTIVE are the whole of ContactStatus. */
const STATUSES: readonly ContactStatus[] = ["ACTIVE", "INACTIVE"];

export function ContactForm({
  initial,
  submitLabel,
  pending = false,
  error,
  onSubmit,
  onCancel,
}: {
  /** The contact being edited. Absent when creating. */
  initial?: Contact;
  submitLabel: string;
  pending?: boolean;
  /** A failed write, reported without discarding what was typed. */
  error?: string;
  onSubmit: (values: ContactFormValues) => void;
  onCancel: () => void;
}) {
  const statusId = useId();
  const editing = initial !== undefined;

  const [values, setValues] = useState<ContactFormValues>({
    fullName: initial?.fullName ?? "",
    email: initial?.email ?? "",
    title: initial?.title ?? "",
    phone: initial?.phone ?? "",
    primaryContact: initial?.primaryContact ?? false,
    status: initial?.status ?? "ACTIVE",
  });
  const [errors, setErrors] = useState<Errors>({});

  function set<K extends keyof ContactFormValues>(field: K, value: ContactFormValues[K]) {
    setValues((previous) => ({ ...previous, [field]: value }));
    // Cleared on edit rather than on the next submit: an error message that
    // outlives the problem trains people to ignore error messages.
    if (field === "fullName" || field === "email") {
      // Rebound through `keyof Errors`: inside the guard `field` is narrowed by
      // value but its type parameter is not, so it cannot index Errors directly.
      const key: keyof Errors = field;
      setErrors((previous) => (previous[key] ? { ...previous, [key]: undefined } : previous));
    }
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault();

    const trimmed: ContactFormValues = {
      fullName: values.fullName.trim(),
      email: values.email.trim(),
      title: values.title.trim(),
      phone: values.phone.trim(),
      primaryContact: values.primaryContact,
      status: values.status,
    };

    // `full_name` and `email` are NOT NULL on customer_contact (V8). Without
    // this a blank submit is a constraint violation surfacing as a 500, which
    // tells the user nothing about which field was wrong.
    const next: Errors = {};
    if (!trimmed.fullName) next.fullName = t("customer.form.required");
    if (!trimmed.email) next.email = t("customer.form.required");
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
          label={t("contact.form.fullName")}
          value={values.fullName}
          error={errors.fullName}
          onChange={(event) => set("fullName", event.target.value)}
        />
        <Field
          label={t("contact.form.email")}
          type="email"
          value={values.email}
          error={errors.email}
          onChange={(event) => set("email", event.target.value)}
        />
        <Field
          label={t("contact.form.title")}
          value={values.title}
          onChange={(event) => set("title", event.target.value)}
        />
        <Field
          label={t("contact.form.phone")}
          type="tel"
          value={values.phone}
          onChange={(event) => set("phone", event.target.value)}
        />

        <PrimaryCheckbox
          checked={values.primaryContact}
          onChange={(checked) => set("primaryContact", checked)}
        />

        {/* Only when editing. The service sets ACTIVE on create and ignores
            anything the caller says about it, so a status control on the create
            form would be a control that cannot affect the outcome.

            This select IS the contact retirement path, and the only one: DELETE
            is deny-by-default at the database layer and business records are
            deactivated rather than deleted, so INACTIVE is the whole of it. It
            sits in the form rather than behind its own confirmation because it
            is reversible from the same place — unlike the customer's
            deactivation, which is a heavier, record-wide act — and because a
            third button on every contact row would crowd out the invitation. */}
        {editing && (
          <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
            <label
              htmlFor={statusId}
              className="text-text-muted"
              style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
            >
              {t("contact.form.status")}
            </label>
            <select
              id={statusId}
              value={values.status}
              onChange={(event) => set("status", event.target.value as ContactStatus)}
              className="bg-surface border border-line text-ink"
              style={{
                height: "var(--ob-control-height)",
                borderRadius: "var(--ob-radius-9)",
                padding: "0 var(--ob-space-11)",
                font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)",
              }}
            >
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {t(`contact.status.${status}`)}
                </option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
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

/**
 * A real `<input type="checkbox">`, which component-specs §11 asks for by name —
 * the prototype's version is a `<button>` carrying no checked state at all.
 *
 * Two deliberate departures from §11, both of which the spec's own rules require:
 *
 * The checked fill is NEUTRAL (`ink`/`surface`), not §11's `solid-on-track`
 * (`ok-fg`) green. "Primary" is a role, not a state, and colour in this system
 * always means status; the contact list marks the same flag with a neutral pill
 * for the same reason. The ink fill with a surface mark is the pairing the
 * pressed filter chip already uses.
 *
 * The unchecked border is `line-strong`, not a literal hex — the semantic tier
 * this design system documents as valid at 3:1 for 1px borders/graphics, not
 * the literal §11 names a value would otherwise have quoted directly.
 *
 * The state is driven from the controlled value rather than `:checked`, because
 * an inline style cannot express a pseudo-class and this component is already
 * the authority on whether the box is ticked.
 */
function PrimaryCheckbox({
  checked,
  onChange,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label
      className="flex items-center text-text-muted"
      style={{
        gap: "var(--ob-space-8)",
        font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
      }}
    >
      <span className="relative inline-flex shrink-0">
        <input
          type="checkbox"
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
          style={{
            appearance: "none",
            margin: 0,
            width: 17,
            height: 17,
            borderRadius: "var(--ob-radius-5)",
            border: `1px solid var(${checked ? "--ob-ink" : "--ob-line-strong"})`,
            background: checked ? "var(--ob-ink)" : "var(--ob-surface)",
            cursor: "pointer",
          }}
        />
        {checked && (
          // Decorative: the label beside it already says what this means, and the
          // input carries the checked state a screen reader reads.
          <span
            aria-hidden="true"
            className="absolute inset-0 grid place-items-center pointer-events-none"
            style={{ color: "var(--ob-surface)" }}
          >
            <CheckIcon size={11} strokeWidth={3} />
          </span>
        )}
      </span>
      {t("contact.form.primary")}
    </label>
  );
}
