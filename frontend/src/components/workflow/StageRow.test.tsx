import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { StageRow } from "./StageRow";
import type { StageDraft } from "./draftState";

afterEach(cleanup);

function stage(overrides: Partial<StageDraft> = {}): StageDraft {
  return { key: "legal", name: "Legal Review", milestones: [], branchRules: [], ...overrides };
}

describe("StageRow", () => {
  it("renders the stage name and its APPROVAL/AUTO badges", () => {
    const s = stage({ requiresApproval: true, autoAdvance: true });
    render(
      <StageRow
        stage={s}
        stages={[s]}
        index={0}
        isFirst
        isLast
        selected={false}
        onSelect={vi.fn()}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(screen.getByText("Legal Review")).not.toBeNull();
  });

  it("calls onSelect when the row is activated", () => {
    const onSelect = vi.fn();
    render(
      <StageRow
        stage={stage()}
        stages={[stage()]}
        index={0}
        isFirst
        isLast
        selected={false}
        onSelect={onSelect}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: /Legal Review/ }));
    expect(onSelect).toHaveBeenCalled();
  });

  /** Delete must not also select the row it just removed. */
  it("calls onDelete without triggering onSelect", () => {
    const onSelect = vi.fn();
    const onDelete = vi.fn();
    render(
      <StageRow
        stage={stage()}
        stages={[stage()]}
        index={0}
        isFirst
        isLast
        selected={false}
        onSelect={onSelect}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={onDelete}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Delete stage" }));
    expect(onDelete).toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("disables move-up on the first stage and move-down on the last", () => {
    render(
      <StageRow
        stage={stage()}
        stages={[stage()]}
        index={0}
        isFirst
        isLast={false}
        selected={false}
        onSelect={vi.fn()}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect((screen.getByRole("button", { name: "Move stage up" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Move stage down" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("shows a branch rule strip naming the target stage, not its raw key", () => {
    const branching = stage({ branchRules: [{ targetStageKey: "live", condition: {} }] });
    const liveStage = stage({ key: "live", name: "Go Live" });
    render(
      <StageRow
        stage={branching}
        stages={[branching, liveStage]}
        index={0}
        isFirst
        isLast
        selected={false}
        onSelect={vi.fn()}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={vi.fn()}
      />,
    );

    expect(screen.getByText("Go Live")).not.toBeNull();
    expect(screen.queryByText("live")).toBeNull();
  });

  /** A published version is frozen: browsing stages must stay possible, reordering or deleting them must not. */
  it("hides the reorder and delete controls when readOnly", () => {
    render(
      <StageRow
        stage={stage()}
        stages={[stage()]}
        index={0}
        isFirst
        isLast
        selected={false}
        onSelect={vi.fn()}
        onMoveUp={vi.fn()}
        onMoveDown={vi.fn()}
        onDelete={vi.fn()}
        readOnly
      />,
    );

    expect(screen.queryByRole("button", { name: "Delete stage" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Move stage up" })).toBeNull();
    expect(screen.getByRole("button", { name: /Legal Review/ })).not.toBeNull();
  });
});
