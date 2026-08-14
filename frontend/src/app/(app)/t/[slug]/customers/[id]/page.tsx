"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { ContactList } from "@/components/customers/ContactList";
import { CustomerForm } from "@/components/customers/CustomerForm";
import type { CustomerFormValues } from "@/components/customers/CustomerForm";
import { ArrowRightIcon, UsersIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Avatar } from "@/components/ui/Avatar";
import { Button } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { Dialog, DialogActions } from "@/components/ui/Dialog";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { ApiError } from "@/lib/api/client";
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

  const canEdit = useHasPermission("customer.edit");
  const canDeactivate = useHasPermission("customer.deactivate");
  const canViewContacts = useHasPermission("contact.view");

  const { data: customer, isLoading, error, refetch } = useCustomer(id);
  const contacts = useContacts(id, canViewContacts);
  const update = useUpdateCustomer();
  const deactivate = useDeactivateCustomer();

  const [editing, setEditing] = useState(false);
  const [confirming, setConfirming] = useState(false);

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
   * Round-tripped, not omitted. PUT replaces every field, and these three back
   * the ASSIGNED, DEPARTMENT and TEAM scope predicates — any one of them dropped
   * would make the record invisible to everyone holding only that scope, from an
   * edit that changed nothing but a display name.
   */
  const { ownerUserId, owningDepartmentId, owningTeamId } = customer;

  const submitEdit = (values: CustomerFormValues) =>
    update.mutate(
      { id, body: { ...values, ownerUserId, owningDepartmentId, owningTeamId } },
      { onSuccess: () => setEditing(false) },
    );

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-grid-gap)" }}>
      <BackLink slug={slug} />

      <Card>
        <div className="flex items-start" style={{ gap: "var(--ob-space-16)" }}>
          {/* Rounded square: a customer is a company. */}
          <Avatar name={customer.displayName ?? ""} kind="company" size={46} />

          <div className="flex-1 min-w-0">
            <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-11)" }}>
              <h2
                className="text-text-primary min-w-0 truncate"
                style={{
                  font: "600 var(--ob-type-19-size)/var(--ob-type-19-line) var(--ob-font-family-ui)",
                  letterSpacing: "var(--ob-type-19-tracking)",
                }}
              >
                {customer.displayName}
              </h2>
              <StatusPill status={customer.status ?? "PROSPECT"} />
            </div>

            <div
              className="flex flex-wrap"
              style={{ gap: "var(--ob-space-28)", marginTop: "var(--ob-space-16)" }}
            >
              <Fact label={t("customer.form.legalName")} value={customer.legalName} />
              <Fact label={t("customer.form.industry")} value={customer.industry} />
              <Fact label={t("customer.form.country")} value={customer.country?.toUpperCase()} mono />
            </div>
          </div>

          <div className="flex shrink-0" style={{ gap: "var(--ob-space-8)" }}>
            {canEdit && (
              <Button type="button" variant="secondary" onClick={() => setEditing(true)}>
                {t("customer.edit")}
              </Button>
            )}
            {/* Deactivation, never deletion — there is no delete action anywhere
                in this product, and already-inactive has nothing to deactivate. */}
            {canDeactivate && customer.status !== "INACTIVE" && (
              <Button type="button" variant="secondary" onClick={() => setConfirming(true)}>
                {t("customer.deactivate")}
              </Button>
            )}
          </div>
        </div>
      </Card>

      {canViewContacts && (
        <ContactList
          customerId={id}
          contacts={contacts.data ?? []}
          isLoading={contacts.isLoading}
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
            className="text-text-secondary"
            style={{ font: "var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
          >
            {t("customer.deactivate.confirm", { name: customer.displayName ?? "" })}
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
      className="inline-flex items-center self-start text-text-muted hover:underline"
      style={{
        gap: "var(--ob-space-6)",
        font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
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
          font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
        }}
      >
        {label}
      </p>
      <p
        className="text-text-primary truncate"
        style={{
          marginTop: "var(--ob-space-4)",
          font: mono
            ? "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-data)"
            : "500 var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
        }}
      >
        {value || "—"}
      </p>
    </div>
  );
}
