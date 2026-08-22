import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { BranchRuleCard } from "./BranchRuleCard";
import type { StageDraft } from "./draftState";

afterEach(cleanup);

const threeStages: StageDraft[] = [
  { key: "reg", name: "Registration", milestones: [], branchRules: [] },
  { key: "legal", name: "Legal Review", milestones: [], branchRules: [] },
  { key: "live", name: "Go Live", milestones: [], branchRules: [] },
];

describe("BranchRuleCard", () => {
  /** Publish rejects a backward branch target, so offering one is offering a 422. */
  it("offers only forward stages as a branch target", () => {
    render(
      <BranchRuleCard
        stageIndex={1}
        stages={threeStages}
        attributes={[]}
        rule={{ condition: {} }}
        onChange={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    const target = screen.getByRole("combobox", { name: /target stage/i });
    expect(within(target).queryByText("Registration")).toBeNull();
    expect(within(target).queryByText("Legal Review")).toBeNull();
    expect(within(target).getByText("Go Live")).not.toBeNull();
  });

  /** A closed dropdown, not free text: a typo cannot silently compile into a condition that never matches. */
  it("offers only declared attributes as condition operands when the source is ATTRIBUTE", () => {
    render(
      <BranchRuleCard
        stageIndex={0}
        stages={threeStages}
        attributes={[{ key: "segment" }, { key: "employeeCount" }]}
        rule={{ condition: { source: "ATTRIBUTE" } }}
        onChange={vi.fn()}
        onRemove={vi.fn()}
      />,
    );

    const field = screen.getByRole("combobox", { name: "Field" });
    expect(within(field).getByText("segment")).not.toBeNull();
    expect(within(field).getByText("employeeCount")).not.toBeNull();
  });

  it("calls onChange with the new target when a stage is selected", () => {
    const onChange = vi.fn();
    render(
      <BranchRuleCard
        stageIndex={0}
        stages={threeStages}
        attributes={[]}
        rule={{ condition: {} }}
        onChange={onChange}
        onRemove={vi.fn()}
      />,
    );

    fireEvent.change(screen.getByRole("combobox", { name: /target stage/i }), { target: { value: "live" } });

    expect(onChange).toHaveBeenCalledWith({ targetStageKey: "live" });
  });

  it("calls onRemove when the remove control is activated", () => {
    const onRemove = vi.fn();
    render(
      <BranchRuleCard
        stageIndex={0}
        stages={threeStages}
        attributes={[]}
        rule={{ condition: {} }}
        onChange={vi.fn()}
        onRemove={onRemove}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Remove branch rule" }));
    expect(onRemove).toHaveBeenCalled();
  });
});
