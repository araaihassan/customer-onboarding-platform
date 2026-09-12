"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, useSearchParams } from "next/navigation";
import { ArrowRightIcon, LayersIcon, SearchIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { StatusPill } from "@/components/ui/StatusPill";
import { useCustomer, useCustomers } from "@/lib/api/customers";
import type { Customer } from "@/lib/api/customers";
import { useProgrammes } from "@/lib/api/programmes";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { useDebounced } from "@/lib/useDebounced";
import { t } from "@/lib/i18n";

/**
 * The programme index -- a customer picker, not a tenant-wide list (Task 29's
 * pre-dispatch plan amendment). The design spec's own API surface (§7) has no
 * `GET /programmes`; `ProgrammeController` only ever lists a single customer's
 * programmes (`GET /customers/{customerId}/programmes`, Task 27.6). Finding a
 * programme therefore means finding its customer first, and this reuses
 * `CustomersPage`'s own search/select pattern to do that rather than inventing
 * a second one.
 *
 * State lives in the URL (`?customer=`), the same convention `WorkBoard`'s
 * `?bucket=` and the case workspace's own tab param already use -- a picked
 * customer survives a refresh and is a link someone can share, not just
 * component state that resets the moment the tab reloads.
 */
export default function ProgrammesPage() {
  const { slug } = useParams<{ slug: string }>();
  const customerId = useSearchParams().get("customer") ?? "";

  const canViewProgrammes = useHasPermission("programme.view");
  const canViewCustomers = useHasPermission("customer.view");

  useSetPageHeader(t("programme.list.title"));

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <h2 className="sr-only">{t("programme.list.title")}</h2>

      {!canViewProgrammes ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("programme.list.noAccess")}
          description={t("programme.list.noAccessHint")}
        />
      ) : customerId ? (
        <CustomerProgrammes slug={slug} customerId={customerId} />
      ) : (
        <CustomerPicker slug={slug} canSearch={canViewCustomers} />
      )}
    </section>
  );
}

/** Step 1: find the customer. Nothing renders below this until one is picked. */
function CustomerPicker({ slug, canSearch }: { slug: string; canSearch: boolean }) {
  const [searchInput, setSearchInput] = useState("");
  const search = useDebounced(searchInput, 250);

  const customers = useCustomers({ search, page: 0 });
  const results = customers.data?.content ?? [];
  const hasSearched = Boolean(search.trim());

  return (
    <>
      <p
        className="text-text-subtle"
        style={{ font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)" }}
      >
        {t("programme.list.pickCustomer")}
      </p>

      <SearchBox value={searchInput} onChange={setSearchInput} disabled={!canSearch} />

      {!canSearch ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("programme.list.noAccess")}
          description={t("programme.list.noAccessHint")}
        />
      ) : !hasSearched ? null : customers.isLoading ? (
        <SkeletonRows rows={4} height={48} />
      ) : customers.isError ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void customers.refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : results.length === 0 ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("customer.list.noMatch")}
          description={t("customer.list.noMatchHint")}
        />
      ) : (
        <ul className="flex flex-col">
          {results.map((customer) => (
            <CustomerResultRow key={customer.id} customer={customer} slug={slug} />
          ))}
        </ul>
      )}
    </>
  );
}

function CustomerResultRow({ customer, slug }: { customer: Customer; slug: string }) {
  return (
    <li className="border-t border-line-faint first:border-t-0">
      <Link
        href={`/t/${slug}/programmes?customer=${customer.id}`}
        className="flex items-center justify-between text-ink hover:underline"
        style={{
          padding: "var(--ob-space-11) 0",
          font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
        }}
      >
        <span className="truncate">{customer.displayName}</span>
        <span aria-hidden="true" style={{ color: "var(--ob-text-faint)" }}>
          <ArrowRightIcon size={14} />
        </span>
      </Link>
    </li>
  );
}

/** Step 2: that customer's programmes. */
function CustomerProgrammes({ slug, customerId }: { slug: string; customerId: string }) {
  const customer = useCustomer(customerId);
  const programmes = useProgrammes(customerId);

  const name = customer.data?.displayName ?? "";
  const list = programmes.data ?? [];

  return (
    <>
      <div className="flex items-center justify-between flex-wrap" style={{ gap: "var(--ob-space-11)" }}>
        <div className="min-w-0">
          <p
            className="text-text-faint"
            style={{
              textTransform: "uppercase",
              letterSpacing: "0.08em",
              font: "var(--ob-type-mono-label-sm-size)/var(--ob-type-mono-label-sm-line) var(--ob-font-family-data)",
            }}
          >
            {t("programme.list.programmesFor")}
          </p>
          <p
            className="text-ink truncate"
            style={{ font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)" }}
          >
            {name || (customer.isLoading ? "…" : "—")}
          </p>
        </div>
        <Link
          href={`/t/${slug}/programmes`}
          className="text-text-subtle hover:underline"
          style={{ font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
        >
          {t("programme.list.changeCustomer")}
        </Link>
      </div>

      {programmes.isLoading ? (
        <SkeletonRows rows={3} height={56} />
      ) : programmes.isError ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void programmes.refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      ) : list.length === 0 ? (
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("programme.list.empty")}
          description={t("programme.list.emptyHint")}
        />
      ) : (
        <ul className="flex flex-col">
          {list.map((programme) => (
            <li
              key={programme.id}
              className="flex items-center justify-between border-t border-line-faint first:border-t-0"
              style={{ padding: "var(--ob-space-11) 0" }}
            >
              <Link
                href={`/t/${slug}/programmes/${programme.id}`}
                className="min-w-0 flex-1 truncate text-ink hover:underline"
                style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
              >
                {programme.name}
              </Link>
              <StatusPill status={programme.status} />
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

function SearchBox({
  value,
  onChange,
  disabled,
}: {
  value: string;
  onChange: (next: string) => void;
  disabled?: boolean;
}) {
  return (
    <div className="relative">
      <span
        aria-hidden="true"
        className="absolute text-text-faint"
        style={{ left: "var(--ob-space-10)", top: "50%", transform: "translateY(-50%)" }}
      >
        <SearchIcon size={14} />
      </span>
      <label className="sr-only" htmlFor="programme-customer-search">
        {t("programme.list.search")}
      </label>
      <input
        id="programme-customer-search"
        type="search"
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        placeholder={t("programme.list.search")}
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
