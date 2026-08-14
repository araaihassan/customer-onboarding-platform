import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
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

function table() {
  return screen.getByRole("table");
}

describe("CustomerTable", () => {
  /**
   * A real <table>, not a div grid with ARIA bolted on. component-specs §7 allows
   * either, but only one of them is free.
   */
  it("names every column with a scoped column header", () => {
    renderTable();
    const headers = within(table()).getAllByRole("columnheader");
    expect(headers.map((h) => h.textContent)).toEqual([
      "Customer",
      "Status",
      "Legal name",
      "Industry",
      "Country",
    ]);
    for (const header of headers) expect(header.getAttribute("scope")).toBe("col");
  });

  /**
   * The specific thing not to copy from the prototype: a <div> with an onClick is
   * not keyboard reachable. The row's primary cell carries a real link.
   */
  it("navigates from a link in the primary cell, not a click handler on the row", () => {
    renderTable();
    const link = within(table()).getByRole("link", { name: /northwind foods/i });
    expect(link.getAttribute("href")).toBe("/t/acme/customers/0199a0c1-0000-7000-8000-00000000abcd");

    for (const row of within(table()).getAllByRole("row")) {
      expect(row.getAttribute("onclick")).toBeNull();
      expect(row.getAttribute("tabindex")).toBeNull();
    }
  });

  /** Colour is never the only signal: the pill carries the word. */
  it("renders status as a pill containing the word", () => {
    renderTable();
    expect(within(table()).getByText("Active")).not.toBeNull();
    expect(within(table()).getByText("On hold")).not.toBeNull();
  });

  /**
   * Rounded-square means a company. The distinction between company and person is
   * doing quiet work across the product, and a customer is always a company.
   */
  it("marks the customer with a rounded-square avatar, never a circle", () => {
    renderTable();
    const row = within(table()).getByRole("row", { name: /northwind foods/i });
    const avatar = row.querySelector<HTMLElement>("[aria-hidden='true']")!;
    expect(avatar.style.borderRadius).toBe("var(--ob-radius-chip)");
    expect(avatar.textContent).toBe("NF");
  });

  /**
   * The design's entity cell is a name over a mono sub-line. The sub-line is the
   * tail of the id, not the head: UUIDv7 encodes a millisecond timestamp in its
   * leading bits, so every record created within about a minute shares the same
   * eight-character prefix.
   */
  it("shows a machine-readable reference under the name", () => {
    renderTable();
    expect(within(table()).getByText("00000000abcd")).not.toBeNull();
  });

  /**
   * Below 1024px the table is replaced by a two-line card list. jsdom performs no
   * layout and applies no media queries, so this asserts only that both
   * presentations exist and are gated by the `lg:` breakpoint classes — it cannot
   * prove that either one is actually hidden at a given width. Task 28's
   * Playwright suite is where that becomes observable.
   */
  it("carries a card list gated to below the lg breakpoint", () => {
    const { container } = renderTable();

    const tableWrapper = container.querySelector("[data-view='table']")!;
    expect(tableWrapper.className).toContain("hidden");
    expect(tableWrapper.className).toContain("lg:block");

    const cardWrapper = container.querySelector("[data-view='cards']")!;
    expect(cardWrapper.className).toContain("lg:hidden");

    // The card keeps the name and the status word — the two things a narrow
    // screen cannot afford to lose.
    const cards = within(cardWrapper as HTMLElement);
    expect(cards.getByRole("link", { name: /northwind foods/i })).not.toBeNull();
    expect(cards.getByText("Active")).not.toBeNull();
  });
});
