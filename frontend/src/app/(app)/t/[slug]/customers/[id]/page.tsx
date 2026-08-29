"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { ContactList } from "@/components/customers/ContactList";
import { CustomerForm } from "@/components/customers/CustomerForm";
import type { CustomerFormValues } from "@/components/customers/CustomerForm";
import { ArrowRightIcon, PlusIcon, UsersIcon } from "@/components/icons";
import { CaseSwitcher } from "@/components/journey/CaseSwitcher";
import { CreateCaseDialog } from "@/components/journey/CreateCaseDialog";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
import { useCases } from "@/lib/api/cases";
import {
  shortId,
  useContacts,
  useCustomer,
  useDeactivateCustomer,
  useUpdateCustomer,
} from "@/lib/api/customers";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * One customer: summary, edit, deactivation and contacts.
 *
 * The shell owns the <h1> and takes the customer's name from the page header
 * context, so everything here starts at <h2>.
 */
export default function CustomerDetailPage() {
  const { slug, id } = useParams<{ slug: string; id: string }>();
  const router = useRouter();

  const canEdit = useHasPermission("customer.edit");
  const canDeactivate = useHasPermission("customer.deactivate");
  const canViewContacts = useHasPermission("contact.view");
  const canViewCases = useHasPermission("case.view");
  const canCreateCase = useHasPermission("case.create");

  const { data: customer, isLoading, error, refetch } = useCustomer(id);
  const contacts = useContacts(id, canViewContacts);
  const cases = useCases(id, canViewCases);
  const update = useUpdateCustomer();
  const deactivate = useDeactivateCustomer();

  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [creatingCase, setCreatingCase] = useState(false);

  const notFound = error instanceof ApiError && error.status === 404;

  useSetPageHeader(
    customer?.displayName ?? "",
    customer ? [shortId(customer.id), customer.country?.toUpperCase()].filter(Boolean).join(" · ") : undefined,
  );

  if (isLoading) return <SkeletonRows rows={5} height={56} />;

  /**
   * A server error is not a missing record, and must not be reported as one:
   * "Not found" would tell the reader their customer is gone when the truth is
   * that the server fell over, and would hide the outage from the one person
   * looking at it.
   */
  if (error && !notFound) {
    return (
      <section>
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      </section>
    );
  }

  /**
   * A 404 renders "Not found" and says nothing about access.
   *
   * The backend answers 404 for a record that does not exist and for one outside
   * the caller's scope, deliberately and identically. Saying "you don't have
   * access to this record" here would hand back exactly the fact the 404 exists
   * to withhold — that the record is real.
   */
  if (notFound || !customer) {
    return (
      <section>
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={t("common.notFound")}
          description={t("customer.notFound.hint")}
          action={<BackLink slug={slug} />}
        />
      </section>
    );
  }

  /**
   * Round-tripped, not omitted. PUT replaces every field, so omitting one is
   * identical to blanking it. The three ownership ids back the ASSIGNED,
   * DEPARTMENT and TEAM scope predicates — any one dropped makes the record
   * invisible to everyone holding only that scope, from an edit that changed
   * nothing but a display name. `externalRef` is the customer's identifier in
   * whatever system it came from and there is no field for it in sub-project 1's
   * form; it is carried through here for the same reason. It became possible to
   * carry in Task R1, which added it to `CustomerView` — before that the API
   * accepted it on write but never returned it, so no client could preserve it.
   */
  const { ownerUserId, owningDepartmentId, owningTeamId, externalRef } = customer;

  const submitEdit = (values: CustomerFormValues) =>
    update.mutate(
      { id, body: { ...values, ownerUserId, owningDepartmentId, owningTeamId, externalRef } },
      { onSuccess: () => setEditing(false) },
    );

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <BackLink slug={slug} />

      <Card>
        <div className="flex items-start" style={{ gap: "var(--ob-space-16)" }}>
          {/* Rounded square: a customer is a company. */}
          <Avatar name={customer.displayName ?? ""} kind="company" size={46} />

          <div className="flex-1 min-w-0">
            <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-11)" }}>
              <h2
                className="text-ink min-w-0 truncate"
                style={{
                  font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)",
                  letterSpacing: "var(--ob-type-section-heading-tracking)",
                }}
              >
                {customer.displayName}
              </h2>
              <StatusPill status={customer.status} />
            </div>

            <div
              className="flex flex-wrap"
              style={{ gap: "var(--ob-space-26)", marginTop: "var(--ob-space-16)" }}
            >
              <Fact label={t("customer.form.legalName")} value={customer.legalName} />
              <Fact label={t("customer.form.industry")} value={customer.industry} />
              <Fact label={t("customer.form.country")} value={customer.country?.toUpperCase()} mono />
            </div>
          </div>

          <div className="flex shrink-0" style={{ gap: "var(--ob-space-8)" }}>
            {canEdit && (
              <Button
                type="button"
                variant="secondary"
                // Reset on open, not on close: a dialog reopened after a failed
                // save would otherwise greet the user with the last attempt's
                // error before they have done anything, and an error that
                // outlives its cause trains people to ignore errors.
                onClick={() => {
                  update.reset();
                  setEditing(true);
                }}
              >
                {t("customer.edit")}
              </Button>
            )}
            {/* Deactivation, never deletion — there is no delete action anywhere
                in this product, and already-inactive has nothing to deactivate. */}
            {canDeactivate && customer.status !== "INACTIVE" && (
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  deactivate.reset();
                  setConfirming(true);
                }}
              >
                {t("customer.deactivate")}
              </Button>
            )}
          </div>
        </div>
      </Card>

      {canViewCases && (
        <Card>
          <h2
            className="text-text-faint"
            style={{
              font: "500 var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              marginBottom: "var(--ob-space-13)",
            }}
          >
            {t("case.switcher.title")}
          </h2>

          {cases.isLoading ? (
            <SkeletonRows rows={1} height={32} />
          ) : cases.isError ? (
            <p className="text-text-subtle" style={{ font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)" }}>
              {t("common.error")}
            </p>
          ) : (cases.data ?? []).length === 0 ? (
            <EmptyState
              title={t("customer.cases.empty")}
              description={t("customer.cases.emptyHint")}
              action={
                canCreateCase && (
                  <Button type="button" variant="secondary" onClick={() => setCreatingCase(true)} style={{ gap: "var(--ob-space-6)" }}>
                    <PlusIcon size={14} />
                    {t("case.switcher.newCase")}
                  </Button>
                )
              }
            />
          ) : (
            <CaseSwitcher
              cases={cases.data ?? []}
              activeCaseId=""
              slug={slug}
              customerId={id}
              canCreate={canCreateCase}
              onCreateNew={() => setCreatingCase(true)}
            />
          )}
        </Card>
      )}

      {creatingCase && (
        <CreateCaseDialog
          customerId={id}
          onCancel={() => setCreatingCase(false)}
          onCreated={(caseId) => {
            setCreatingCase(false);
            router.push(`/t/${slug}/customers/${id}/cases/${caseId}`);
          }}
        />
      )}

      {canViewContacts && (
        <ContactList
          customerId={id}
          contacts={contacts.data ?? []}
          isLoading={contacts.isLoading}
          // A failed contacts read used to render "No contacts yet", which is a
          // statement about the data rather than about the request.
          isError={contacts.isError}
          onRetry={() => void contacts.refetch()}
        />
      )}

      {editing && (
        <Dialog title={t("customer.edit.title")} onClose={() => setEditing(false)}>
          <CustomerForm
            initial={customer}
            submitLabel={t("common.save")}
            pending={update.isPending}
            error={update.isError ? t("common.error") : undefined}
            onSubmit={submitEdit}
            onCancel={() => setEditing(false)}
          />
        </Dialog>
      )}

      {confirming && (
        <Dialog title={t("customer.deactivate.title")} onClose={() => setConfirming(false)}>
          <p
            className="text-text-muted"
            style={{ font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)" }}
          >
            {t("customer.deactivate.confirm", { name: customer.displayName ?? "" })}
          </p>

          {/* Rendered empty rather than conditionally, so the live region exists
              before there is anything to announce — several screen readers only
              watch regions that were already present when the change happened.
              An empty <p> has no line box, so it costs no height until it fills.
              Same failure the invitation button had: a destructive action that
              fails silently leaves the user believing it worked. */}
          <p
            role="alert"
            style={{
              color: "var(--ob-risk-fg)",
              marginTop: deactivate.isError ? "var(--ob-space-11)" : 0,
              font: "var(--ob-type-row-subtitle-size)/var(--ob-type-row-subtitle-line) var(--ob-font-family-ui)",
            }}
          >
            {deactivate.isError ? t("common.error") : ""}
          </p>

          <DialogActions>
            <Button type="button" variant="secondary" onClick={() => setConfirming(false)}>
              {t("common.cancel")}
            </Button>
            <Button
              type="button"
              disabled={deactivate.isPending}
              onClick={() =>
                deactivate.mutate({ id }, { onSuccess: () => setConfirming(false) })
              }
            >
              {t("common.confirm")}
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </section>
  );
}

function BackLink({ slug }: { slug: string }) {
  return (
    <Link
      href={`/t/${slug}/customers`}
      className="inline-flex items-center self-start text-text-subtle hover:underline"
      style={{
        gap: "var(--ob-space-6)",
        font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
      }}
    >
      <span aria-hidden="true" style={{ transform: "rotate(180deg)", display: "inline-flex" }}>
        <ArrowRightIcon size={14} />
      </span>
      {t("customer.detail.back")}
    </Link>
  );
}

/**
 * A fact column from the record header: a mono uppercase label over the value.
 * The label is mono because it is a field name in a machine-shaped grid; the
 * value is mono only when the value itself is machine-generated.
 */
function Fact({
  label,
  value,
  mono = false,
}: {
  label: string;
  value?: string;
  mono?: boolean;
}) {
  return (
    <div className="min-w-0">
      <p
        className="text-text-faint"
        style={{
          textTransform: "uppercase",
          letterSpacing: "0.08em",
          font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
        }}
      >
        {label}
      </p>
      <p
        className="text-ink truncate"
        style={{
          marginTop: "var(--ob-space-4)",
          font: mono
            ? "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-data)"
            : "500 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
        }}
      >
        {value || "—"}
      </p>
    </div>
  );
}
