import { describe, expect, it } from "vitest";
import { toStageDraft } from "./toDraft";
import { useDraftState } from "./draftState";
import { act, renderHook } from "@testing-library/react";

describe("toStageDraft (view -> draft)", () => {
  it("carries pausesOnCustomer=false through unchanged, so a save sends false and never omits it", () => {
    const draft = toStageDraft({ key: "s1", name: "Docs", pausesOnCustomer: false, milestones: [], branchRules: [] });
    expect(draft.pausesOnCustomer).toBe(false);

    const { result } = renderHook(() => useDraftState([draft]));
    // This is exactly the body the page PUTs: JSON.stringify drops undefined, keeps false.
    const body = JSON.parse(JSON.stringify({ stages: result.current.stages }));
    expect(body.stages[0]).toHaveProperty("pausesOnCustomer", false);
  });

  it("carries true through, and an editing round trip keeps the value", () => {
    const draft = toStageDraft({ key: "s1", name: "Docs", pausesOnCustomer: true, milestones: [], branchRules: [] });
    const { result } = renderHook(() => useDraftState([draft]));
    act(() => result.current.updateStage("s1", { name: "Renamed" }));
    expect(result.current.stages[0]!.pausesOnCustomer).toBe(true);
  });

  it("marks the draft dirty when the toggle is changed", () => {
    const draft = toStageDraft({ key: "s1", name: "Docs", pausesOnCustomer: true, milestones: [], branchRules: [] });
    const { result } = renderHook(() => useDraftState([draft]));
    expect(result.current.dirty).toBe(false);
    act(() => result.current.updateStage("s1", { pausesOnCustomer: false }));
    expect(result.current.dirty).toBe(true);
  });
});
