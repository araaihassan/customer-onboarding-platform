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

const signatureMilestone: MilestoneRequest[] = [
  {
    key: "m1",
    name: "Contracting",
    estimatedDurationDays: 2,
    requirements: [
      { kind: "SIGNATURE", label: "Sign MSA", weight: 1, mandatory: true, agreementRecordMode: "FILE_BACKED", agreementName: "MSA" },
    ],
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

  it("offers SIGNATURE as a requirement kind", () => {
    render(<MilestoneEditor milestones={oneMilestone} onChange={vi.fn()} />);
    const kind = screen.getByLabelText("Requirement kind") as HTMLSelectElement;
    expect(Array.from(kind.options).map((o) => o.value)).toContain("SIGNATURE");
  });

  it("shows record-mode and agreement-name fields only for a SIGNATURE requirement", () => {
    const { unmount } = render(<MilestoneEditor milestones={oneMilestone} onChange={vi.fn()} />);
    expect(screen.queryByLabelText("Agreement record mode")).toBeNull();
    expect(screen.queryByLabelText("Agreement name")).toBeNull();
    unmount();

    render(<MilestoneEditor milestones={signatureMilestone} onChange={vi.fn()} />);
    const mode = screen.getByLabelText("Agreement record mode") as HTMLSelectElement;
    expect(mode.value).toBe("FILE_BACKED");
    expect(Array.from(mode.options).map((o) => o.textContent)).toEqual([
      "File-backed",
      "Structured + file",
      "Structured record only",
    ]);
    expect((screen.getByLabelText("Agreement name") as HTMLInputElement).value).toBe("MSA");
  });

  it("writes agreementRecordMode and agreementName into the requirement patch", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={signatureMilestone} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText("Agreement record mode"), { target: { value: "STRUCTURED_ONLY" } });
    expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ requirements: [expect.objectContaining({ kind: "SIGNATURE", agreementRecordMode: "STRUCTURED_ONLY" })] }),
    ]);

    fireEvent.change(screen.getByLabelText("Agreement name"), { target: { value: "Master agreement" } });
    expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ requirements: [expect.objectContaining({ kind: "SIGNATURE", agreementName: "Master agreement" })] }),
    ]);
  });

  it("clears the agreement fields when the kind changes away from SIGNATURE", () => {
    const onChange = vi.fn();
    render(<MilestoneEditor milestones={signatureMilestone} onChange={onChange} />);

    fireEvent.change(screen.getByLabelText("Requirement kind"), { target: { value: "MANUAL" } });

    const patched = onChange.mock.calls[0]![0][0].requirements[0];
    expect(patched.kind).toBe("MANUAL");
    expect("agreementRecordMode" in patched).toBe(true);
    expect("agreementName" in patched).toBe(true);
    expect(patched.agreementRecordMode).toBeUndefined();
    expect(patched.agreementName).toBeUndefined();
  });
});
