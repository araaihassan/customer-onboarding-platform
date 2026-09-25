import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MilestoneEditor } from "./MilestoneEditor";
import type { MilestoneRequest } from "@/lib/api/workflows";

afterEach(cleanup);

const oneMilestone: MilestoneRequest[] = [
  { key: "m1", name: "KYC Pack", estimatedDurationDays: 2, requirements: [{ kind: "MANUAL", label: "Collect ID", weight: 1, mandatory: true }] },
];

const documentRequirementMilestone: MilestoneRequest[] = [
  {
    key: "m1",
    name: "Document collection",
    estimatedDurationDays: 2,
    requirements: [{ kind: "DOCUMENT", label: "Tax certificate", weight: 1, mandatory: true }],
  },
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

  it("hides the document category select and requires-review checkbox for a non-DOCUMENT requirement", () => {
    render(<MilestoneEditor milestones={oneMilestone} onChange={vi.fn()} />);
    expect(screen.queryByLabelText("Document category")).toBeNull();
    expect(screen.queryByRole("checkbox", { name: "Requires review" })).toBeNull();
  });

  it("shows the document category select and requires-review checkbox for a DOCUMENT requirement, defaulting to Other and unchecked", () => {
    render(<MilestoneEditor milestones={documentRequirementMilestone} onChange={vi.fn()} />);
    expect((screen.getByLabelText("Document category") as HTMLSelectElement).value).toBe("OTHER");
    expect((screen.getByRole("checkbox", { name: "Requires review" }) as HTMLInputElement).checked).toBe(false);
  });

  it("changing the document category reaches onChange with documentCategory set", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={documentRequirementMilestone} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText("Document category"), { target: { value: "TAX" } });

    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        requirements: [expect.objectContaining({ kind: "DOCUMENT", documentCategory: "TAX" })],
      }),
    ]);
  });

  it("toggling requires-review reaches onChange with requiresReview set", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={documentRequirementMilestone} onChange={onChange} />);

    fireEvent.click(screen.getByRole("checkbox", { name: "Requires review" }));

    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        requirements: [expect.objectContaining({ kind: "DOCUMENT", requiresReview: true })],
      }),
    ]);
  });

});
