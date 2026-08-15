"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { t } from "@/lib/i18n";

/**
 * The person being added to a customer.
 *
 * Exactly `CreateContactRequest`'s five fields and no more. `status` is not here
 * because the service sets it to ACTIVE, and `userId` is not here because it
 * stays null until the portal invitation is accepted (spec 9.1, QA Q12) — a
 * control for either would let the interface claim something the API ignores.
 */
export type ContactFormValues = {
  fullName: string;
  email: string;
  title: string;
  phone: string;
  primaryContact: boolean;
};

type Errors = Partial<Record<"fullName" | "email", string>>;

export function ContactForm({
  pending = false,
  error,
  onSubmit,
  onCancel,
}: {
  pending?: boolean;
  /** A failed create, reported without discarding what was typed. */
  error?: string;
  onSubmit: (values: ContactFormValues) => void;
  onCancel: () => void;
}) {
  const [values, setValues] = useState<ContactFormValues>({
    fullName: "",
    email: "",
    title: "",
    phone: "",
    primaryContact: false,
  });
  const [errors, setErrors] = useState<Errors>({});

  function set(field: keyof ContactFormValues, value: string | boolean) {
    setValues((previous) => ({ ...previous, [field]: value }));
    // Cleared on edit rather than on the next submit: an error message that
    // outlives the problem trains people to ignore error messages.
    if (field === "fullName" || field === "email") {
      setErrors((previous) => (previous[field] ? { ...previous, [field]: undefined } : previous));
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

        {/* A real <input type="checkbox">, which component-specs §11 asks for by
            name — the prototype's button carries no checked state at all.

            The fill is neutral rather than the §11 `solid-on-track` green:
            "primary" is a role, not a state, and colour in this system always
            means status. The contact list marks the same flag with a neutral
            pill for the same reason. */}
        <label
          className="flex items-center text-text-secondary"
          style={{
            gap: "var(--ob-space-8)",
            font: "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
          }}
        >
          <input
            type="checkbox"
            checked={values.primaryContact}
            onChange={(event) => set("primaryContact", event.target.checked)}
            style={{
              width: "var(--ob-checkbox-size)",
              height: "var(--ob-checkbox-size)",
              borderRadius: "var(--ob-radius-check)",
              accentColor: "var(--ob-text-primary)",
            }}
          />
          {t("contact.form.primary")}
        </label>
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
          {t("contact.create.submit")}
        </Button>
      </DialogActions>
    </form>
  );
}
