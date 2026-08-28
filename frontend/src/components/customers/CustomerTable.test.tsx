import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, within } from "@testing-library/react";
import type { Customer } from "@/lib/api/customers";

vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { CustomerTable } = await import("./CustomerTable");

const customers: Customer[] = [
  {
    id: "0199a0c1-0000-7000-8000-00000000abcd",
    displayName: "Northwind Foods",
    legalName: "Northwind Foods Holdings Ltd",
    status: "ACTIVE",
    industry: "Food & Beverage",
    country: "GB",
  },
  {
    id: "0199a0c1-0000-7000-8000-0000000012ef",
    displayName: "Halden Rail",
    legalName: "Halden Rail AS",
    status: "ON_HOLD",
    industry: "Transport",
    country: "NO",
  },
];

afterEach(cleanup);

function renderTable() {
  return render(<CustomerTable customers={customers} slug="acme" />);
}

/**
 * `CustomerTable` now composes `DataTable` (Task 22), a generic CSS-grid table
 * primitive rather than a real `<table>` -- that architectural call was made
 * and reviewed clean in Task 22, not this task. One direct consequence: there
 * is no `table`/`columnheader`/`cell` role or `scope="col"` to assert on
 * anymore, since `DataTable`'s own markup never had them (only its header row
 * carries `role="row"`). Every query below is scoped to the `[data-view]`
 * wrapper it cares about, because `DataTable` mounts BOTH the grid and the
 * `<900px` card list at once (CSS-gated visibility, not conditional
 * rendering) -- an unscoped query would see duplicate text from whichever
 * view isn't the one under test.
 */
function tableWrapper(container: HTMLElement): HTMLElement {
  return container.querySelector("[data-view='table']") as HTMLElement;
}

function cardsWrapper(container: HTMLElement): HTMLElement {
  return container.querySelector("[data-view='cards']") as HTMLElement;
}

describe("CustomerTable", () => {
  it("names every column across the header row", () => {
    const { container } = renderTable();
    const headerRow = within(tableWrapper(container)).getByRole("row");
    const headers = Array.from(headerRow.children).map((el) => el.textContent);
    expect(headers).toEqual(["Customer", "Status", "Legal name", "Industry", "Country"]);
  });

  /**
   * The specific thing not to copy from the prototype: a <div> with an onClick is
   * not keyboard reachable. The row's primary cell carries a real link, and
   * (unlike `DataTable`'s own optional `onRowClick`, unused here) nothing turns
   * the row itself into a button.
   */
  it("navigates from a link in the primary cell, not a row-level control", () => {
    const { container } = renderTable();
    const table = tableWrapper(container);
    const link = within(table).getByRole("link", { name: /northwind foods/i });
    expect(link.getAttribute("href")).toBe("/t/acme/customers/0199a0c1-0000-7000-8000-00000000abcd");

    expect(within(table).queryAllByRole("button")).toHaveLength(0);
  });

  /** Colour is never the only signal: the pill carries the word. */
  it("renders status as a pill containing the word", () => {
    const { container } = renderTable();
    const table = tableWrapper(container);
    expect(within(table).getByText("Active")).not.toBeNull();
    expect(within(table).getByText("On hold")).not.toBeNull();
  });

  /**
   * A missing status must not become a made-up one. Industry and country fall
   * back to an em dash and nothing is claimed; a defaulted status is a coloured,
   * worded assertion about the record that no data supports — which is the
   * "colour always means status" rule, not a style preference.
   */
  it("says nothing rather than inventing a status that is absent", () => {
    const { container } = render(
      <CustomerTable
        customers={[{ id: "0199a0c1-0000-7000-8000-000000000001", displayName: "Unknown Co" }]}
        slug="acme"
      />,
    );

    const table = tableWrapper(container);
    expect(within(table).queryByText("Prospect")).toBeNull();

    // The status cell specifically, addressed by DataTable's own
    // data-column marker rather than a cell role (there is none). The first
    // match is the header label ("Status"); the second is this row's cell.
    const statusCell = table.querySelectorAll("[data-column='status']")[1]!;
    expect(statusCell.textContent).toBe("—");
  });

  /**
   * Rounded-square means a company. The distinction between company and person is
   * doing quiet work across the product, and a customer is always a company.
   */
  it("marks the customer with a rounded-square avatar, never a circle", () => {
    const { container } = renderTable();
    const table = tableWrapper(container);
    // The first match is the header label ("Customer"); the second is this
    // row's entity cell.
    const entityCell = table.querySelectorAll("[data-column='customer']")[1]!;
    const avatar = entityCell.querySelector<HTMLElement>("[aria-hidden='true']")!;
    expect(avatar.style.borderRadius).toBe("var(--ob-radius-5)");
    expect(avatar.textContent).toBe("NF");
  });

  /**
   * The design's entity cell is a name over a mono sub-line. The sub-line is the
   * tail of the id, not the head: UUIDv7 encodes a millisecond timestamp in its
   * leading bits, so every record created within about a minute shares the same
   * eight-character prefix.
   */
  it("shows a machine-readable reference under the name", () => {
    const { container } = renderTable();
    expect(within(tableWrapper(container)).getByText("00000000abcd")).not.toBeNull();
  });

  /**
   * Below 900px (`SCREENS.md`'s RESPONSIVE table) the grid is replaced by a
   * two-line card list carrying the name, the status word and the mono
   * reference line -- via `DataTable`'s `stackedColumn` prop. This is the same
   * shape as the `MigrationTable` empty-state-guard bug already in CLAUDE.md: a
   * fallback that silently drops rows is a regression, so this proves every
   * row survives, not just the first.
   */
  it("carries a card list gated below 900px, and drops no rows", () => {
    const { container } = renderTable();

    const table = tableWrapper(container);
    expect(table.className).toContain("hidden");
    expect(table.className).toContain("min-[900px]:block");

    const cards = cardsWrapper(container);
    expect(cards.className).toContain("min-[900px]:hidden");

    const cardItems = within(cards).getAllByRole("listitem");
    expect(cardItems).toHaveLength(customers.length);

    // Both customers' names and statuses are present in the card list, not
    // just the first one.
    expect(within(cards).getByRole("link", { name: /northwind foods/i })).not.toBeNull();
    expect(within(cards).getByRole("link", { name: /halden rail/i })).not.toBeNull();
    expect(within(cards).getByText("Active")).not.toBeNull();
    expect(within(cards).getByText("On hold")).not.toBeNull();
  });
});
