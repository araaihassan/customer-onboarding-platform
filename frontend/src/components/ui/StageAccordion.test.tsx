import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
// Registers toBeInTheDocument() etc. on vitest's expect -- imported locally,
// same as DataTable.test.tsx, rather than widening the global vitest config.
import "@testing-library/jest-dom/vitest";
import { StageAccordion } from "./StageAccordion";

afterEach(cleanup);

describe("StageAccordion", () => {
  it("renders the header and toggles the panel via a button click", () => {
    const onToggle = vi.fn();
    render(
      <StageAccordion number={4} title="Document collection" meta="2/3 milestones · Operations · 5d estimated" progressPercent={20} statusChip={<span>IN PROGRESS</span>} isOpen={false} onToggle={onToggle}>
        <div>Milestone content</div>
      </StageAccordion>,
    );
    expect(screen.getByText("Document collection")).toBeInTheDocument();
    expect(screen.queryByText("Milestone content")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /document collection/i }));
    expect(onToggle).toHaveBeenCalledOnce();
  });

  it("renders the expanded panel when isOpen", () => {
    render(
      <StageAccordion number={1} title="Registration" meta="" progressPercent={100} statusChip={<span>COMPLETE</span>} isOpen onToggle={vi.fn()}>
        <div>Milestone content</div>
      </StageAccordion>,
    );
    expect(screen.getByText("Milestone content")).toBeInTheDocument();
  });
});
