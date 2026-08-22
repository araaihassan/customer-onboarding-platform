import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
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

function renderTable(candidates: Candidate[], overrides: Partial<Parameters<typeof MigrationTable>[0]> = {}) {
  const props = {
    candidates,
    slug: "acme",
    selected: new Set<string>(),
    onToggle: vi.fn(),
    onSelectAll: vi.fn(),
    ...overrides,
  };
  render(<MigrationTable {...props} />);
  return props;
}

describe("MigrationTable", () => {
  it("shows the computed reason for every ineligible case", () => {
    renderTable(mixed);
    expect(screen.getByText(/no longer exists in the new version/i)).not.toBeNull();
  });

  it("disables selection for ineligible rows", () => {
    renderTable(mixed);
    const ineligibleCheckbox = screen.getByLabelText(`Select case ${ineligibleCase.caseId}`) as HTMLInputElement;
    expect(ineligibleCheckbox.disabled).toBe(true);

    const eligibleCheckbox = screen.getByLabelText(`Select case ${eligibleCase.caseId}`) as HTMLInputElement;
    expect(eligibleCheckbox.disabled).toBe(false);
  });

  it("calls onToggle with the case id when an eligible row's checkbox is clicked", () => {
    const props = renderTable(mixed);
    fireEvent.click(screen.getByLabelText(`Select case ${eligibleCase.caseId}`));
    expect(props.onToggle).toHaveBeenCalledWith(eligibleCase.caseId);
  });

  it("selects all eligible rows without selecting any ineligible one, via the header checkbox", () => {
    const props = renderTable(mixed);
    fireEvent.click(screen.getByLabelText("Select all eligible"));
    expect(props.onSelectAll).toHaveBeenCalled();
  });

  it("renders the empty state only when there are no candidates at all", () => {
    renderTable([]);
    expect(screen.getByText(/nothing eligible to migrate/i)).not.toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("still renders the table and the reason when every candidate is ineligible", () => {
    // The whole point of this table: "0 eligible" without a reason is a
    // number an admin cannot act on. Migrating the only eligible case must
    // not blank out the ineligible rows still waiting on a fix.
    renderTable([ineligibleCase]);
    expect(screen.getByRole("table")).not.toBeNull();
    expect(screen.getByText(/no longer exists in the new version/i)).not.toBeNull();
  });
});
