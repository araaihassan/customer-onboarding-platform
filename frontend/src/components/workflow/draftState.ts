"use client";

import { useMemo, useReducer } from "react";
import type { AttributeRequest, StageRequest } from "@/lib/api/workflows";

/**
 * A stage mid-edit always carries its client-local `key` — branch rules and
 * `fallbackNextStageKey` reference it, and the server assigns real ids only on
 * save (WorkflowDefinitionView.key is echoed back as the persisted stage's own
 * id, never the client-local key, per that view's own javadoc).
 */
export type StageDraft = StageRequest & { key: string };
export type AttributeDraft = AttributeRequest & { key: string };

type State = {
  stages: StageDraft[];
  attributes: AttributeDraft[];
  selectedKey: string | null;
  savedStages: StageDraft[];
  savedAttributes: AttributeDraft[];
};

type Action =
  | { type: "select"; key: string | null }
  | { type: "moveUp"; key: string }
  | { type: "moveDown"; key: string }
  | { type: "removeStage"; key: string }
  | { type: "addStage"; stage: StageDraft }
  | { type: "updateStage"; key: string; patch: Partial<StageDraft> }
  | { type: "addAttribute"; attribute: AttributeDraft }
  | { type: "updateAttribute"; key: string; patch: Partial<AttributeDraft> }
  | { type: "removeAttribute"; key: string }
  | { type: "reset"; stages: StageDraft[]; attributes: AttributeDraft[] };

function moveStage(stages: StageDraft[], key: string, delta: number): StageDraft[] {
  const index = stages.findIndex((s) => s.key === key);
  const target = index + delta;
  if (index < 0 || target < 0 || target >= stages.length) return stages;

  const next = stages.slice();
  const [item] = next.splice(index, 1);
  next.splice(target, 0, item as StageDraft);
  return next;
}

function reducer(state: State, action: Action): State {
  switch (action.type) {
    case "select":
      return { ...state, selectedKey: action.key };
    case "moveUp":
      return { ...state, stages: moveStage(state.stages, action.key, -1) };
    case "moveDown":
      return { ...state, stages: moveStage(state.stages, action.key, 1) };
    case "removeStage":
      return {
        ...state,
        stages: state.stages.filter((s) => s.key !== action.key),
        selectedKey: state.selectedKey === action.key ? null : state.selectedKey,
      };
    case "addStage":
      return { ...state, stages: [...state.stages, action.stage], selectedKey: action.stage.key };
    case "updateStage":
      return {
        ...state,
        stages: state.stages.map((s) => (s.key === action.key ? { ...s, ...action.patch } : s)),
      };
    case "addAttribute":
      return { ...state, attributes: [...state.attributes, action.attribute] };
    case "updateAttribute":
      return {
        ...state,
        attributes: state.attributes.map((a) => (a.key === action.key ? { ...a, ...action.patch } : a)),
      };
    case "removeAttribute":
      return { ...state, attributes: state.attributes.filter((a) => a.key !== action.key) };
    case "reset":
      return {
        stages: action.stages,
        attributes: action.attributes,
        // Kept when the selected stage survived the round trip (the common
        // case: Save just re-fetched the same graph with server-assigned
        // ids) -- losing the inspector's focus on every successful Save
        // would be a worse experience than a save that changed nothing the
        // admin was looking at.
        selectedKey: action.stages.some((s) => s.key === state.selectedKey) ? state.selectedKey : null,
        savedStages: action.stages,
        savedAttributes: action.attributes,
      };
    default:
      return state;
  }
}

/**
 * The STRUCTURAL half of PublishService's own validation: a branch rule or
 * fallback naming a stage key this draft no longer has. Surfacing it the
 * moment a stage is deleted is a courtesy — the server remains the sole
 * authority, and a 422 is never suppressed because the client thought the
 * graph was fine.
 */
function computeProblems(stages: StageDraft[]): string[] {
  const keys = new Set(stages.map((s) => s.key));
  const problems: string[] = [];

  for (const stage of stages) {
    const danglingTargets = [
      stage.fallbackNextStageKey,
      ...(stage.branchRules ?? []).map((rule) => rule.targetStageKey),
    ].filter((key): key is string => Boolean(key) && !keys.has(key as string));

    if (danglingTargets.length > 0) {
      problems.push(`${stage.name} branches to a stage that no longer exists`);
    }

    // source/operator always carry a value (addBranchRule seeds both), but the
    // field and target stay empty until the admin picks one -- branch_rule's
    // key/target_stage_id columns are NOT NULL, so an incomplete rule would
    // otherwise surface as a raw constraint violation on Save rather than a
    // problem the admin can act on before it ever reaches the server.
    const incomplete = (stage.branchRules ?? []).some((rule) => !rule.condition?.key || !rule.targetStageKey);
    if (incomplete) {
      problems.push(`${stage.name} has a branch rule that is missing a field or a target stage`);
    }
  }
  return problems;
}

/**
 * Holds one workflow version's whole graph as a client-side draft. Reordering
 * is the ▲▼ buttons the prototype draws, not drag-and-drop: keyboard-operable
 * and announceable for free, on a screen an admin touches twice a year.
 *
 * `dirty` compares against the draft's OWN saved snapshot, updated only by
 * `reset` — never against the caller's live server data, which can change
 * identity across renders for reasons that have nothing to do with what the
 * admin has edited.
 */
export function useDraftState(initialStages: StageDraft[], initialAttributes: AttributeDraft[] = []) {
  const [state, dispatch] = useReducer(reducer, {
    stages: initialStages,
    attributes: initialAttributes,
    selectedKey: null,
    savedStages: initialStages,
    savedAttributes: initialAttributes,
  });

  const problems = useMemo(() => computeProblems(state.stages), [state.stages]);
  const dirty = useMemo(
    () =>
      JSON.stringify(state.stages) !== JSON.stringify(state.savedStages) ||
      JSON.stringify(state.attributes) !== JSON.stringify(state.savedAttributes),
    [state.stages, state.attributes, state.savedStages, state.savedAttributes],
  );

  return {
    stages: state.stages,
    attributes: state.attributes,
    selectedKey: state.selectedKey,
    problems,
    dirty,
    select: (key: string | null) => dispatch({ type: "select", key }),
    moveUp: (key: string) => dispatch({ type: "moveUp", key }),
    moveDown: (key: string) => dispatch({ type: "moveDown", key }),
    removeStage: (key: string) => dispatch({ type: "removeStage", key }),
    addStage: (stage: StageDraft) => dispatch({ type: "addStage", stage }),
    updateStage: (key: string, patch: Partial<StageDraft>) => dispatch({ type: "updateStage", key, patch }),
    addAttribute: (attribute: AttributeDraft) => dispatch({ type: "addAttribute", attribute }),
    updateAttribute: (key: string, patch: Partial<AttributeDraft>) =>
      dispatch({ type: "updateAttribute", key, patch }),
    removeAttribute: (key: string) => dispatch({ type: "removeAttribute", key }),
    /** Rebases the dirty comparison -- called after a successful save, or when the caller loads a different version. */
    reset: (stages: StageDraft[], attributes: AttributeDraft[] = []) =>
      dispatch({ type: "reset", stages, attributes }),
  };
}

export type DraftState = ReturnType<typeof useDraftState>;

/** A fresh client-local key. Not a UUID: nothing server-side ever reads this format, and a short, readable key is what an admin sees echoed in error messages. */
export function newDraftKey(prefix: string): string {
  return `${prefix}-${Math.random().toString(36).slice(2, 10)}`;
}
