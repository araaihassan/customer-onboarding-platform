import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
// Registers toBeInTheDocument() etc. on vitest's expect -- imported locally,
// same as DataTable.test.tsx / StageAccordion.test.tsx, rather than widening
// the global vitest config.
import "@testing-library/jest-dom/vitest";
import { BuilderNode } from "./BuilderNode";

afterEach(cleanup);

describe("BuilderNode", () => {
  it("renders the name, meta and milestone pills, and calls onClick", () => {
    const onClick = vi.fn();
    render(
      <BuilderNode name="Agreement" teamMeta="Legal · 8d" milestonePills={["MSA drafted", "Legal review"]} onClick={onClick} />,
    );
    expect(screen.getByText("Agreement")).toBeInTheDocument();
    expect(screen.getByText("MSA drafted")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /agreement/i }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("renders a CONDITIONAL chip and the branch number-tile fill when isBranch", () => {
    render(<BuilderNode name="Segment is ENTERPRISE?" teamMeta="" milestonePills={[]} isBranch conditionalChip onClick={vi.fn()} />);
    expect(screen.getByText("CONDITIONAL")).toBeInTheDocument();
  });
});
