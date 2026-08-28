import Link from "next/link";
import type { ReactNode } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { DataTable } from "@/components/ui/DataTable";
import { StatusPill } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Customer } from "@/lib/api/customers";
import { t } from "@/lib/i18n";

/**
 * The customer table (component-specs §7), composing the shared `DataTable`
 * primitive (Task 22, `COMPONENTS.md` §12).
 *
 * **Columns.** The design's customer screen lists *cases*, not companies — its
 * six columns are name, stage, progress, owner, due date and health, and the
 * §7 "Cells" catalogue that names a progress cell and an overdue-date cell is a
 * catalogue across every table in the product, not a description of this one
 * (it also lists a "Visible to" cell, which belongs to the documents table).
 * Cases arrive in sub-project 2. `CustomerView` carries display name, legal
 * name, status, industry, country and three ownership ids — no stage, no
 * progress, no health, no dates — so this table renders the five columns that
 * exist rather than inventing four that do not. The design's fr shares —
 * 2.1 / 1.2 / 1.5 / 1 / 1 — are `DataTable`'s own `width` per column, in fr
 * units directly (a percentage conversion was only needed for a real
 * `<table>`'s `colgroup`, which this component no longer has).
 *
 * **Rows.** A row's primary cell holds a real link, not a click handler on the
 * row (`DataTable`'s own `onRowClick` is deliberately unused here) — the
 * prototype's `<div onClick>` is not keyboard reachable and appears in both
 * the dashboard and this list, which is precisely why it is the pattern not
 * to copy.
 *
 * **Below 900px** (`SCREENS.md`'s RESPONSIVE table) `DataTable`'s
 * `stackedColumn` replaces the grid with a two-line card list carrying the
 * name, the status word and the mono reference line. A table cannot be made
 * to fit a phone by shrinking it; below the breakpoint it stops being a
 * table.
 */
export function CustomerTable({ customers, slug }: { customers: Customer[]; slug: string }) {
  const columns: { key: string; label: string; width: string; render: (customer: Customer) => ReactNode }[] = [
    {
      key: "customer",
      label: t("customer.table.customer"),
      width: "2.1fr",
      render: (customer) => <EntityCell customer={customer} slug={slug} />,
    },
    {
      key: "status",
      label: t("customer.table.status"),
      width: "1.2fr",
      render: (customer) => <StatusPill status={customer.status} />,
    },
    {
      key: "legalName",
      label: t("customer.table.legalName"),
      width: "1.5fr",
      render: (customer) => (
        <span className="block truncate text-text-muted" style={BODY_TEXT}>
          {customer.legalName}
        </span>
      ),
    },
    {
      key: "industry",
      label: t("customer.table.industry"),
      width: "1fr",
      render: (customer) => (
        <span className="block truncate text-text-subtle" style={BODY_TEXT}>
          {customer.industry || EMPTY}
        </span>
      ),
    },
    {
      key: "country",
      label: t("customer.table.country"),
      width: "1fr",
      // An ISO 3166-1 alpha-2 code is a machine value, so it is mono — the
      // same rule that makes ids and counts mono and the industry beside it
      // not.
      render: (customer) => (
        <span className="text-text-subtle" style={MONO_TEXT}>
          {customer.country ? customer.country.toUpperCase() : EMPTY}
        </span>
      ),
    },
  ];

  return (
    <DataTable
      columns={columns}
      rows={customers}
      getRowKey={(customer) => customer.id ?? ""}
      stackedColumn={(customer) => <CustomerCard customer={customer} slug={slug} />}
    />
  );
}

/** An em dash, so a missing optional value reads as absent rather than broken. */
const EMPTY = "—";

/** Table cell (`DESIGN_TOKENS.md` Typography: 12.5px/400, 600 for the identifying column). */
const BODY_TEXT = {
  font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
} as const;

/** Mono data (10–11px) — case IDs, dates, durations, counts, and here an ISO country code. */
const MONO_TEXT = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;

/**
 * `COMPONENTS.md` §12: "the identifying column: 12.5–13px/600 with an 11px
 * text-subtle subtitle underneath" — the reference line under the name.
 */
const MONO_SUBLINE = {
  font: "var(--ob-type-mono-data-size)/var(--ob-type-mono-data-line) var(--ob-font-family-data)",
} as const;

/**
 * The entity cell: a rounded-square avatar — a customer is a company — the name
 * as the row's link, and a mono reference beneath it.
 */
function EntityCell({ customer, slug }: { customer: Customer; slug: string }) {
  return (
    <div className="flex items-center min-w-0" style={{ gap: "var(--ob-space-11)" }}>
      <Avatar name={customer.displayName ?? ""} kind="company" />
      <div className="min-w-0">
        <CustomerLink customer={customer} slug={slug} />
        <p className="truncate text-text-subtle" style={MONO_SUBLINE}>
          {shortId(customer.id)}
        </p>
      </div>
    </div>
  );
}

function CustomerLink({ customer, slug }: { customer: Customer; slug: string }) {
  return (
    <Link
      href={`/t/${slug}/customers/${customer.id}`}
      className="block truncate text-ink hover:underline"
      style={{ font: "600 var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
    >
      {customer.displayName}
    </Link>
  );
}

/**
 * The `<900px` card: avatar, name link, a combined reference/country/industry
 * subline, and the status pill — the two-line card the design calls for,
 * moved here (from `CustomerTable`'s own markup) as `DataTable`'s
 * `stackedColumn` render prop.
 */
function CustomerCard({ customer, slug }: { customer: Customer; slug: string }) {
  return (
    <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
      <Avatar name={customer.displayName ?? ""} kind="company" />
      <div className="flex-1 min-w-0">
        <CustomerLink customer={customer} slug={slug} />
        <p className="truncate text-text-subtle" style={MONO_SUBLINE}>
          {[shortId(customer.id), customer.country?.toUpperCase(), customer.industry]
            .filter(Boolean)
            .join(" · ")}
        </p>
      </div>
      <StatusPill status={customer.status} />
    </div>
  );
}
