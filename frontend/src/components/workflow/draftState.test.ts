import { describe, expect, it } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { useDraftState, type StageDraft } from "./draftState";

function stage(key: string, name: string, overrides: Partial<StageDraft> = {}): StageDraft {
  return { key, name, milestones: [], branchRules: [], ...overrides };
}

const threeStages: StageDraft[] = [stage("reg", "Registration"), stage("legal", "Legal Review"), stage("live", "Go Live")];

describe("useDraftState", () => {
  it("keeps the selection when a stage moves", () => {
    const { result } = renderHook(() => useDraftState(threeStages));

    act(() => result.current.select("legal"));
    act(() => result.current.moveUp("legal"));

    expect(result.current.selectedKey).toBe("legal");
    expect(result.current.stages.map((s) => s.key)).toEqual(["legal", "reg", "live"]);
  });

  it("does nothing when moving the first stage up, or the last stage down", () => {
    const { result } = renderHook(() => useDraftState(threeStages));

    act(() => result.current.moveUp("reg"));
    expect(result.current.stages.map((s) => s.key)).toEqual(["reg", "legal", "live"]);

    act(() => result.current.moveDown("live"));
    expect(result.current.stages.map((s) => s.key)).toEqual(["reg", "legal", "live"]);
  });

  it("surfaces an error before Save when a deleted stage is a branch target", () => {
    const smbBranchesToGoLive: StageDraft[] = [
      stage("reg", "Registration", { branchRules: [{ targetStageKey: "live", condition: {} }] }),
      stage("legal", "Legal Review"),
      stage("live", "Go Live"),
    ];
    const { result } = renderHook(() => useDraftState(smbBranchesToGoLive));

    act(() => result.current.removeStage("live"));

    expect(result.current.problems).toContainEqual(
      expect.stringContaining("Registration branches to a stage that no longer exists"),
    );
  });

  it("also catches a dangling fallbackNextStageKey", () => {
    const stages: StageDraft[] = [
      stage("reg", "Registration", { fallbackNextStageKey: "live" }),
      stage("live", "Go Live"),
    ];
    const { result } = renderHook(() => useDraftState(stages));

    act(() => result.current.removeStage("live"));

    expect(result.current.problems).toContainEqual(
      expect.stringContaining("Registration branches to a stage that no longer exists"),
    );
  });

  it("has no problems when every branch target still exists", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    expect(result.current.problems).toEqual([]);
  });

  /** branch_rule.key and target_stage_id are both NOT NULL -- an incomplete rule must surface before Save, not as a raw DB error after it. */
  it("flags a branch rule that is missing a field or a target stage", () => {
    const incomplete: StageDraft[] = [
      stage("reg", "Registration", { branchRules: [{ condition: { source: "ATTRIBUTE", operator: "EQ" } }] }),
      stage("legal", "Legal Review"),
    ];
    const { result } = renderHook(() => useDraftState(incomplete));

    expect(result.current.problems).toContainEqual(
      expect.stringContaining("Registration has a branch rule that is missing a field or a target stage"),
    );
  });

  /** Order IS the ordinal (StageRequest carries none) -- removal alone is the whole story. */
  it("leaves array order dense after a removal, with nothing left to renumber", () => {
    const { result } = renderHook(() => useDraftState(threeStages));

    act(() => result.current.removeStage("legal"));

    expect(result.current.stages.map((s) => s.key)).toEqual(["reg", "live"]);
  });

  it("marks the draft dirty after an edit, and clean again after reset", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    expect(result.current.dirty).toBe(false);

    act(() => result.current.updateStage("reg", { name: "Sign-up" }));
    expect(result.current.dirty).toBe(true);

    act(() => result.current.reset(result.current.stages, result.current.attributes));
    expect(result.current.dirty).toBe(false);
  });

  /** A successful Save re-fetches the same graph; losing inspector focus every time would be worse than a no-op save. */
  it("keeps the selection across a reset when the selected stage survives", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    act(() => result.current.select("legal"));

    act(() => result.current.reset(result.current.stages, result.current.attributes));

    expect(result.current.selectedKey).toBe("legal");
  });

  it("drops the selection across a reset when the selected stage did not survive", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    act(() => result.current.select("legal"));

    act(() => result.current.reset(threeStages.filter((s) => s.key !== "legal"), []));

    expect(result.current.selectedKey).toBeNull();
  });

  it("clears the selection when the selected stage is removed", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    act(() => result.current.select("legal"));
    act(() => result.current.removeStage("legal"));
    expect(result.current.selectedKey).toBeNull();
  });

  it("adds a new stage and selects it", () => {
    const { result } = renderHook(() => useDraftState(threeStages));
    act(() => result.current.addStage(stage("new", "New Stage")));

    expect(result.current.stages.map((s) => s.key)).toEqual(["reg", "legal", "live", "new"]);
    expect(result.current.selectedKey).toBe("new");
  });
});
