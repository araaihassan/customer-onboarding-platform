"use client";

import { useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { Field } from "@/components/ui/Field";
import { ApiError } from "@/lib/api/client";
import { useCustomers } from "@/lib/api/customers";
import { useCloneTemplate, type WorkflowTemplate } from "@/lib/api/workflows";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { useDebounced } from "@/lib/useDebounced";
import { t } from "@/lib/i18n";

/**
 * QA Q21's clone gesture (CustomerTemplateService.clone, Task 16): tailors one
 * catalogue template for exactly one customer, one clone per (source, customer)
 * pair -- V18's partial unique index is the last line of defense, a 409 here
 * from the service's own pre-check is the first.
 *
 * The customer picker is a debounced search over useCustomers -- the same
 * shape ProgrammesPage's own CustomerPicker already established for the same
 * reason: a tenant's customer list does not fit a plain unpaged <select>.
 * Typing narrows the <select>'s own options rather than opening a second
 * overlay, matching ProgrammeParticipants' simpler "search filters this
 * select" shape.
 *
 * Cloning only ever produces a DRAFT (never published), and the response
 * carries no versionId (WorkflowTemplateView's own shape -- see
 * useCloneTemplate's doc comment) -- so, unlike refresh, there is nowhere to
 * route to on success. Closing the dialog and letting the newly segmented
 * customer-template row's own "Start editing" button open it (through the
 * same 409-conflict resume path a template's very first draft already uses)
 * is the whole story.
 */
export function CloneTemplateDialog({
  template,
  open,
  onClose,
}: {
  /** The catalogue template being cloned. */
  template: WorkflowTemplate;
  open: boolean;
  onClose: () => void;
}) {
  const canViewCustomers = useHasPermission("customer.view");
  const clone = useCloneTemplate();

  const [name, setName] = useState("");
  const [nameError, setNameError] = useState<string>();
  const [customerSearch, setCustomerSearch] = useState("");
  const [customerId, setCustomerId] = useState("");
  const [customerError, setCustomerError] = useState<string>();

  const search = useDebounced(customerSearch, 250);
  const customers = useCustomers({ search, page: 0 });
  const results = customers.data?.content ?? [];

  if (!open) return null;

  function submit() {
    const trimmedName = name.trim();
    let ok = true;
    if (!trimmedName) {
      setNameError(t("customer.form.required"));
      ok = false;
    }
    if (!customerId) {
      setCustomerError(t("customer.form.required"));
      ok = false;
    }
    if (!ok || !template.id) return;

    clone.mutate(
      { templateId: template.id, body: { customerId, name: trimmedName } },
      { onSuccess: onClose },
    );
  }

  return (
    <Dialog title={t("workflow.cloneDialog.title", { name: template.name ?? "" })} onClose={onClose}>
      <div className="flex flex-col" style={{ gap: "var(--ob-space-13)" }}>
        <Field
          label={t("workflow.cloneDialog.name")}
          value={name}
          error={nameError}
          onChange={(event) => {
            setName(event.target.value);
            setNameError(undefined);
          }}
        />

        {canViewCustomers ? (
          <>
            <Field
              label={t("workflow.cloneDialog.customerSearch")}
              type="search"
              value={customerSearch}
              onChange={(event) => setCustomerSearch(event.target.value)}
            />

            <div className="flex flex-col" style={{ gap: "var(--ob-space-6)" }}>
              <label
                htmlFor="clone-customer-select"
                className="text-text-muted"
                style={{ font: "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {t("workflow.cloneDialog.customer")}
              </label>
              <select
                id="clone-customer-select"
                value={customerId}
                aria-invalid={customerError ? true : undefined}
                onChange={(event) => {
                  setCustomerId(event.target.value);
                  setCustomerError(undefined);
                }}
                className="bg-surface border border-line text-ink"
                style={{
                  height: "var(--ob-control-height)",
                  borderRadius: "var(--ob-radius-9)",
                  padding: "0 var(--ob-space-11)",
                  font: "13px/1.3 var(--ob-font-family-ui)",
                  borderColor: customerError ? "var(--ob-risk-fg)" : undefined,
                }}
              >
                <option value="">{t("common.select")}</option>
                {results.map((customer) => (
                  <option key={customer.id} value={customer.id}>
                    {customer.displayName ?? customer.id}
                  </option>
                ))}
              </select>
              {customerError ? (
                <p style={{ color: "var(--ob-risk-fg)", font: "11.5px var(--ob-font-family-ui)" }}>
                  {customerError}
                </p>
              ) : (
                <p className="text-text-faint" style={{ font: "11.5px var(--ob-font-family-ui)" }}>
                  {t("workflow.cloneDialog.customerHint")}
                </p>
              )}
            </div>
          </>
        ) : (
          <p
            className="text-text-faint"
            style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}
          >
            {t("workflow.cloneDialog.noCustomerAccess")}
          </p>
        )}
      </div>

      {clone.isError && (
        <p
          role="alert"
          style={{
            color: "var(--ob-risk-fg)",
            marginTop: "var(--ob-space-11)",
            font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
          }}
        >
          {clone.error instanceof ApiError && clone.error.status === 409
            ? t("workflow.cloneDialog.duplicate")
            : t("common.error")}
        </p>
      )}

      <DialogActions>
        <Button type="button" variant="secondary" onClick={onClose}>
          {t("common.cancel")}
        </Button>
        <Button type="button" disabled={clone.isPending} onClick={submit}>
          {t("workflow.cloneDialog.submit")}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
