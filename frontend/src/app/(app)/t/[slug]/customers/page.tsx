"use client";

import { useState } from "react";
import { useParams, useRouter } from "next/navigation";
import { CustomerTable } from "@/components/customers/CustomerTable";
import { CustomerForm } from "@/components/customers/CustomerForm";
import type { CustomerFormValues } from "@/components/customers/CustomerForm";
import { PlusIcon, SearchIcon, UsersIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { Pagination } from "@/components/ui/Pagination";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { CUSTOMER_STATUSES, useCreateCustomer, useCustomers } from "@/lib/api/customers";
import type { CustomerStatus } from "@/lib/api/customers";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { useDebounced } from "@/lib/useDebounced";
import { t } from "@/lib/i18n";

/**
 * The customer list: search, a status filter and pagination over
 * `GET /customers`.
 *
 * The screen title belongs to the shell header, which owns the <h1>; everything
 * here starts at <h2>.
 */
export default function CustomersPage() {
  const { slug } = useParams<{ slug: string }>();
  const router = useRouter();

  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 250);
  const [status, setStatus] = useState<CustomerStatus | null>(null);
  const [page, setPage] = useState(0);
  const [creating, setCreating] = useState(false);

  const canCreate = useHasPermission("customer.create");
  const { data, isLoading, isError, isFetching, refetch } = useCustomers({ search, status, page });
  const create = useCreateCustomer();

  useSetPageHeader(t("customer.list.title"));

  const customers = data?.content ?? [];
  const totalPages = data?.totalPages ?? 0;
  const totalElements = data?.totalElements ?? 0;
  const filtered = Boolean(search.trim()) || status !== null;

  /**
   * Page 3 of a filter that no longer applies is a blank screen with no
   * explanation, so any change to what is being asked for returns to page 1.
   */
  function refilter(next: () => void) {
    next();
    setPage(0);
  }

  function submitNew(values: CustomerFormValues) {
    create.mutate(values, {
      onSuccess: (customer) => {
        setCreating(false);
        router.push(`/t/${slug}/customers/${customer.id}`);
      },
    });
  }

  return (
    <section>
      <h2 className="sr-only">{t("customer.list.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)", marginBottom: "var(--ob-space-16)" }}
      >
        <SearchBox
          value={searchInput}
          onChange={(next) => refilter(() => setSearchInput(next))}
        />

        <div
          role="group"
          aria-label={t("customer.list.filter")}
          className="flex flex-wrap items-center"
          style={{ gap: "var(--ob-space-6)" }}
        >
          <Button
            type="button"
            variant={status === null ? "filter-active" : "filter-idle"}
            aria-pressed={status === null}
            onClick={() => refilter(() => setStatus(null))}
            style={{ whiteSpace: "nowrap" }}
          >
            {t("customer.filter.all")}
          </Button>
          {CUSTOMER_STATUSES.map((value) => (
            <Button
              key={value}
              type="button"
              variant={status === value ? "filter-active" : "filter-idle"}
              aria-pressed={status === value}
              onClick={() => refilter(() => setStatus(value))}
              style={{ whiteSpace: "nowrap" }}
            >
              {t(`customer.status.${value}`)}
            </Button>
          ))}
        </div>

        <div className="flex-1" />

        {/* A count is a machine-generated value, so it is mono. */}
        {!isLoading && (
          <span
            className="text-text-subtle whitespace-nowrap"
            style={{ font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)" }}
          >
            {t("customer.list.count", { count: String(totalElements) })}
          </span>
        )}

        {canCreate && (
          <Button
            type="button"
            // Reset on open: a dialog reopened after a failed save would
            // otherwise show the last attempt's error before the user has done
            // anything, and an error that outlives its cause trains people to
            // ignore errors.
            onClick={() => {
              create.reset();
              setCreating(true);
            }}
            style={{ gap: "var(--ob-space-6)" }}
          >
            <PlusIcon size={14} />
            {t("customer.create.title")}
          </Button>
        )}
      </div>

      {isLoading ? (
        <SkeletonRows rows={6} height={56} />
      ) : isError ? (
        // Error states are one of the design's declared gaps. An error with no
        // way out of it is a dead end, so it carries the retry.
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : customers.length === 0 ? (
        <EmptyState
          icon={<UsersIcon size={28} />}
          title={filtered ? t("customer.list.noMatch") : t("customer.list.empty")}
          description={filtered ? t("customer.list.noMatchHint") : t("customer.list.emptyHint")}
        />
      ) : (
        <>
          <CustomerTable customers={customers} slug={slug} />
          {totalPages > 1 && (
            <Pagination
              label={t("customer.page.nav")}
              page={page}
              totalPages={totalPages}
              onChange={setPage}
              // isFetching, not isLoading: the previous page stays on screen
              // while the next one loads, so without this a second click would
              // queue a page the user never sees the first of.
              disabled={isFetching}
            />
          )}
        </>
      )}

      {creating && (
        <Dialog title={t("customer.create.title")} onClose={() => setCreating(false)}>
          <CustomerForm
            submitLabel={t("customer.create.submit")}
            pending={create.isPending}
            error={create.isError ? t("common.error") : undefined}
            onSubmit={submitNew}
            onCancel={() => setCreating(false)}
          />
        </Dialog>
      )}
    </section>
  );
}

function SearchBox({ value, onChange }: { value: string; onChange: (next: string) => void }) {
  return (
    <div className="relative">
      <span
        aria-hidden="true"
        className="absolute text-text-faint"
        style={{ left: "var(--ob-space-10)", top: "50%", transform: "translateY(-50%)" }}
      >
        <SearchIcon size={14} />
      </span>
      {/* A real <label>, visually hidden. A placeholder is not an accessible
          name and disappears the moment someone types. */}
      <label className="sr-only" htmlFor="customer-search">
        {t("customer.list.search")}
      </label>
      <input
        id="customer-search"
        type="search"
        value={value}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t("customer.list.search")}
        className="bg-surface border border-line text-ink"
        style={{
          height: "var(--ob-control-height)",
          width: 260,
          borderRadius: "var(--ob-radius-9)",
          padding: "0 var(--ob-space-11) 0 var(--ob-space-26)",
          font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)",
        }}
      />
    </div>
  );
}
