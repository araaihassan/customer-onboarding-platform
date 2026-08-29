import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, within } from "@testing-library/react";
import { MigrationTable } from "./MigrationTable";
import type { Candidate } from "@/lib/api/workflows";

afterEach(cleanup);

const eligibleCase: Candidate = {
  caseId: "01a0000e-0000-7000-8000-000000000001",
  customerId: "01a0000e-0000-7000-8000-0000000000c1",
  currentStageName: "Registration",
  eligible: true,
  reason: undefined,
};

const ineligibleCase: Candidate = {
  caseId: "01a0000e-0000-7000-8000-000000000002",
  customerId: "01a0000e-0000-7000-8000-0000000000c2",
  currentStageName: "Legal Review",
  eligible: false,
  reason: "Stage 'Legal Review' has already been completed but no longer exists in the new version",
};

const mixed = [eligibleCase, ineligibleCase];

/**
 * `MigrationTable` now composes `DataTable` (Task 34), which mounts BOTH the
 * `>=900px` grid and the `<900px` card list at once (CSS-gated visibility,
 * not conditional rendering -- `DataTable`'s own doc comment explains why:
 * it is what keeps a responsive fallback from ever silently dropping a row,
 * the same failure shape as the `candidates.length === 0` guard this file
 * exists to protect). Every row's content therefore appears twice in the
 * DOM, so queries below are scoped to the grid view via this helper, the
 * same pattern `CustomerTable.test.tsx` established for its own Task 27
 * conversion, rather than to `screen` directly.
 */
function tableWrapper(container: HTMLElement): HTMLElement | null {
  return container.querySelector("[data-view='table']");
}

function renderTable(candidates: Candidate[], overrides: Partial<Parameters<typeof MigrationTable>[0]> = {}) {
  const props = {
    candidates,
    slug: "acme",
    selected: new Set<string>(),
    onToggle: vi.fn(),
    onSelectAll: vi.fn(),
    ...overrides,
  };
  const { container } = render(<MigrationTable {...props} />);
  return { ...props, container };
}

describe("MigrationTable", () => {
  it("shows the computed reason for every ineligible case", () => {
    const { container } = renderTable(mixed);
    const table = tableWrapper(container)!;
    expect(within(table).getByText(/no longer exists in the new version/i)).not.toBeNull();
  });

  it("disables selection for ineligible rows", () => {
    const { container } = renderTable(mixed);
    const table = tableWrapper(container)!;
    const ineligibleCheckbox = within(table).getByLabelText(`Select case ${ineligibleCase.caseId}`) as HTMLInputElement;
    expect(ineligibleCheckbox.disabled).toBe(true);

    const eligibleCheckbox = within(table).getByLabelText(`Select case ${eligibleCase.caseId}`) as HTMLInputElement;
    expect(eligibleCheckbox.disabled).toBe(false);
  });

  it("calls onToggle with the case id when an eligible row's checkbox is clicked", () => {
    const { container, onToggle } = renderTable(mixed);
    const table = tableWrapper(container)!;
    fireEvent.click(within(table).getByLabelText(`Select case ${eligibleCase.caseId}`));
    expect(onToggle).toHaveBeenCalledWith(eligibleCase.caseId);
  });

  it("selects all eligible rows without selecting any ineligible one, via the select-all control", () => {
    const { container, onSelectAll } = renderTable(mixed);
    // The select-all control sits just above the grid/card views (Task 34's
    // doc comment on `MigrationTable` explains why: `DataTable`'s header
    // cells only ever render a plain string label, so there is nowhere
    // inside the generated header row for an interactive control to live),
    // so it is queried from the whole container rather than a scoped view.
    fireEvent.click(within(container).getByLabelText("Select all eligible"));
    expect(onSelectAll).toHaveBeenCalled();
  });

  it("renders the empty state only when there are no candidates at all", () => {
    const { container } = renderTable([]);
    expect(within(container).getByText(/nothing eligible to migrate/i)).not.toBeNull();
    expect(tableWrapper(container)).toBeNull();
  });

  it("still renders the table and the reason when every candidate is ineligible", () => {
    // The whole point of this table: "0 eligible" without a reason is a
    // number an admin cannot act on. Migrating the only eligible case must
    // not blank out the ineligible rows still waiting on a fix.
    const { container } = renderTable([ineligibleCase]);
    const table = tableWrapper(container);
    expect(table).not.toBeNull();
    expect(within(table!).getByText(/no longer exists in the new version/i)).not.toBeNull();
  });
});
