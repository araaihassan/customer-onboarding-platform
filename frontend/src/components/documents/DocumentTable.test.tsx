import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, within } from "@testing-library/react";
import { DocumentTable } from "./DocumentTable";
import type { Document } from "@/lib/api/documents";

afterEach(cleanup);

/**
 * `DataTable` mounts both the >=900px grid and the <900px card list at once
 * (CSS-gated visibility, not conditional rendering), so every row's content
 * appears twice in the DOM -- queries below are scoped to the grid view,
 * the same pattern `MigrationTable.test.tsx` established.
 */
function tableWrapper(container: HTMLElement): HTMLElement | null {
  return container.querySelector("[data-view='table']");
}

const companyShared: Document = {
  id: "01a0000e-0000-7000-8000-000000000001",
  caseId: "01a0000e-0000-7000-8000-000000000010",
  customerId: "01a0000e-0000-7000-8000-0000000000c1",
  name: "Tax certificate.pdf",
  category: "TAX",
  visibilityTier: "COMPANY_SHARED",
  status: "ACTIVE",
};

const sensitive: Document = {
  id: "01a0000e-0000-7000-8000-000000000002",
  caseId: "01a0000e-0000-7000-8000-000000000010",
  customerId: "01a0000e-0000-7000-8000-0000000000c2",
  name: "Beneficial ownership.xlsx",
  category: "KYC",
  visibilityTier: "SENSITIVE",
  status: "ACTIVE",
};

describe("DocumentTable", () => {
  it("renders the empty state when there are no documents", () => {
    const { container } = render(<DocumentTable documents={[]} slug="acme" />);
    expect(within(container).getByText(/no documents yet/i)).not.toBeNull();
    expect(tableWrapper(container)).toBeNull();
  });

  it("renders a lock glyph immediately before a sensitive document's filename, and not for a non-sensitive one", () => {
    const { container } = render(<DocumentTable documents={[companyShared, sensitive]} slug="acme" />);
    const table = tableWrapper(container)!;

    const sharedCell = within(table).getByText("Tax certificate.pdf").closest("span[data-column='document']") as HTMLElement;
    expect(sharedCell.querySelector("svg")).toBeNull();

    const sensitiveCell = within(table)
      .getByText("Beneficial ownership.xlsx")
      .closest("span[data-column='document']") as HTMLElement;
    const icon = sensitiveCell.querySelector("svg");
    expect(icon).not.toBeNull();
    // Immediately before the filename: the icon is the name text's preceding sibling.
    const nameSpan = within(sensitiveCell).getByText("Beneficial ownership.xlsx");
    expect(nameSpan.previousElementSibling?.tagName.toLowerCase()).toBe("svg");
  });

  it("renders the visibility cell's Chip word for every row, never colour alone", () => {
    const { container } = render(<DocumentTable documents={[companyShared, sensitive]} slug="acme" />);
    const table = tableWrapper(container)!;
    // The tier's word appears twice per row (the Chip's humanised prose and
    // the mono scope label beneath it -- both are the same field, see
    // `VisibilityCell`'s own doc comment), so this asserts presence via a
    // case-sensitive match against the Chip's own humanised form only.
    expect(within(table).getByText("Company shared")).not.toBeNull();
    expect(within(table).getByText("Sensitive")).not.toBeNull();
  });

  it("renders the document's category, humanised", () => {
    const { container } = render(<DocumentTable documents={[companyShared]} slug="acme" />);
    const table = tableWrapper(container)!;
    expect(within(table).getByText("Tax")).not.toBeNull();
  });

  it("links the customer cell to the customer page", () => {
    const { container } = render(<DocumentTable documents={[companyShared]} slug="acme" />);
    const table = tableWrapper(container)!;
    const link = within(table).getByRole("link");
    expect(link.getAttribute("href")).toBe(`/t/acme/customers/${companyShared.customerId}`);
  });
});
