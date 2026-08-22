import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MilestoneEditor } from "./MilestoneEditor";
import type { MilestoneRequest } from "@/lib/api/workflows";

afterEach(cleanup);

const oneMilestone: MilestoneRequest[] = [
  { key: "m1", name: "KYC Pack", estimatedDurationDays: 2, requirements: [{ kind: "MANUAL", label: "Collect ID", weight: 1, mandatory: true }] },
];

describe("MilestoneEditor", () => {
  it("renders each milestone's name and duration", () => {
    render(<MilestoneEditor milestones={oneMilestone} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue("KYC Pack")).not.toBeNull();
    expect(screen.getByDisplayValue("2")).not.toBeNull();
  });

  it("adds a new milestone", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={[]} onChange={onChange} />);

    fireEvent.click(screen.getByText("Add milestone"));

    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ name: "", estimatedDurationDays: 1, requirements: [] }),
    ]);
  });

  it("removes a milestone", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={oneMilestone} onChange={onChange} />);

    fireEvent.click(screen.getByRole("button", { name: "Remove milestone" }));

    expect(onChange).toHaveBeenCalledWith([]);
  });

  it("renders each milestone's requirements, with a checkbox for mandatory", () => {
    render(<MilestoneEditor milestones={oneMilestone} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue("Collect ID")).not.toBeNull();
    expect(screen.getByRole("checkbox", { name: "Mandatory" })).not.toBeNull();
  });

  it("adds a requirement to a milestone", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={oneMilestone} onChange={onChange} />);

    fireEvent.click(screen.getByText("Add requirement"));

    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        requirements: [
          expect.objectContaining({ label: "Collect ID" }),
          expect.objectContaining({ kind: "MANUAL", label: "", mandatory: true }),
        ],
      }),
    ]);
  });
});
