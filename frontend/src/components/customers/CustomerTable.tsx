import Link from "next/link";
import type { ReactNode } from "react";
import { Avatar } from "@/components/ui/Avatar";
import { StatusPill } from "@/components/ui/StatusPill";
import { shortId } from "@/lib/api/customers";
import type { Customer } from "@/lib/api/customers";
import { t } from "@/lib/i18n";

/**
 * The customer table (component-specs §7).
 *
 * **Columns.** The design's customer screen lists *cases*, not companies — its
 * six columns are name, stage, progress, owner, due date and health, and the
 * §7 "Cells" catalogue that names a progress cell and an overdue-date cell is a
 * catalogue across every table in the product, not a description of this one
 * (it also lists a "Visible to" cell, which belongs to the documents table).
 * Cases arrive in sub-project 2. `CustomerView` carries display name, legal
 * name, status, industry, country and three ownership ids — no stage, no
 * progress, no health, no dates — so this table renders the five columns that
 * exist rather than inventing four that do not. The design's fr shares are kept
 * for the columns that remain: 2.1 / 1.2 / 1.5 / 1 / 1, converted to
 * percentages because `fr` is not a table width.
 *
 * **Rows.** A row's primary cell holds a real link. The prototype's
 * `<div onClick>` is not keyboard reachable and appears in both the dashboard
 * and this list, which is precisely why it is the pattern not to copy.
 *
 * **Below 1024px** the table is replaced by a two-line card list carrying the
 * name, the status word and the mono reference line. A table cannot be made to
 * fit a phone by shrinking it; below the breakpoint it stops being a table.
 */
const COLUMN_WIDTHS = ["30.88%", "17.65%", "22.06%", "14.71%", "14.71%"];

export function CustomerTable({ customers, slug }: { customers: Customer[]; slug: string }) {
  return (
    <>
      <div
        data-view="table"
        className="hidden lg:block bg-bg-surface border border-border-default rounded-card overflow-hidden"
      >
        <table className="w-full" style={{ borderCollapse: "collapse", tableLayout: "fixed" }}>
          <colgroup>
            {COLUMN_WIDTHS.map((width) => (
              <col key={width} style={{ width }} />
            ))}
          </colgroup>
          <thead>
            <tr className="bg-bg-surface-subtle">
              <ColumnHeader>{t("customer.table.customer")}</ColumnHeader>
              <ColumnHeader>{t("customer.table.status")}</ColumnHeader>
              <ColumnHeader>{t("customer.table.legalName")}</ColumnHeader>
              <ColumnHeader>{t("customer.table.industry")}</ColumnHeader>
              <ColumnHeader>{t("customer.table.country")}</ColumnHeader>
            </tr>
          </thead>
          <tbody>
            {customers.map((customer) => (
              <tr
                key={customer.id}
                className="border-t border-border-subtle hover:bg-bg-surface-subtle"
              >
                <Cell>
                  <EntityCell customer={customer} slug={slug} />
                </Cell>
                <Cell>
                  <StatusPill status={customer.status ?? "PROSPECT"} />
                </Cell>
                <Cell>
                  <span className="block truncate text-text-secondary" style={BODY_TEXT}>
                    {customer.legalName}
                  </span>
                </Cell>
                <Cell>
                  <span className="block truncate text-text-muted" style={BODY_TEXT}>
                    {customer.industry || EMPTY}
                  </span>
                </Cell>
                <Cell>
                  {/* An ISO 3166-1 alpha-2 code is a machine value, so it is
                      mono — the same rule that makes ids and counts mono and
                      the industry beside it not. */}
                  <span className="text-text-muted" style={MONO_TEXT}>
                    {customer.country ? customer.country.toUpperCase() : EMPTY}
                  </span>
                </Cell>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <ul data-view="cards" className="lg:hidden flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
        {customers.map((customer) => (
          <li
            key={customer.id}
            className="bg-bg-surface border border-border-default rounded-card"
            style={{ padding: "var(--ob-space-13) var(--ob-space-16)" }}
          >
            <div className="flex items-center" style={{ gap: "var(--ob-space-11)" }}>
              <Avatar name={customer.displayName ?? ""} kind="company" />
              <div className="flex-1 min-w-0">
                <CustomerLink customer={customer} slug={slug} />
                <p className="truncate text-text-faint" style={MONO_SUBLINE}>
                  {[shortId(customer.id), customer.country?.toUpperCase(), customer.industry]
                    .filter(Boolean)
                    .join(" · ")}
                </p>
              </div>
              <StatusPill status={customer.status ?? "PROSPECT"} />
            </div>
          </li>
        ))}
      </ul>
    </>
  );
}

/** An em dash, so a missing optional value reads as absent rather than broken. */
const EMPTY = "—";

const BODY_TEXT = {
  font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)",
} as const;

const MONO_TEXT = {
  font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-data)",
} as const;

const MONO_SUBLINE = {
  font: "var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
} as const;

function ColumnHeader({ children }: { children: ReactNode }) {
  return (
    <th
      scope="col"
      className="text-left text-text-faint"
      style={{
        padding: "var(--ob-space-8) var(--ob-table-row-padding-x)",
        textTransform: "uppercase",
        letterSpacing: "0.08em",
        font: "var(--ob-type-9-5-size)/var(--ob-type-9-5-line) var(--ob-font-family-data)",
      }}
    >
      {children}
    </th>
  );
}

function Cell({ children }: { children: ReactNode }) {
  return (
    <td
      className="align-middle"
      style={{ padding: "var(--ob-table-row-padding-y) var(--ob-table-row-padding-x)" }}
    >
      {children}
    </td>
  );
}

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
        <p className="truncate text-text-faint" style={MONO_SUBLINE}>
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
      className="block truncate text-text-primary hover:underline"
      style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
    >
      {customer.displayName}
    </Link>
  );
}
