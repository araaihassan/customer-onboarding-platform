import { describe, expect, it } from "vitest";
import { toAttributeDraft, toMilestoneRequest, toStageDraft } from "./toDraft";
import type {
  Attribute,
  AttributeRequest,
  Milestone,
  MilestoneRequest,
  Requirement,
  RequirementRequest,
  Stage,
  StageRequest,
} from "@/lib/api/workflows";
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

describe("view -> draft -> PUT body round trip (derived from the generated types)", () => {
  // Every key of the generated view type must be set to a non-default value:
  // `Required<...>` makes tsc fail here when the schema gains a field.
  const requirement: Required<Requirement> = {
    id: "r-id", kind: "DOCUMENT", label: "Passport", weight: 7, mandatory: false, documentCategory: "IDENTITY",
    approverRelationship: "APPROVER", requiresReview: true, agreementRecordMode: "STRUCTURED_ONLY", agreementName: "MSA",
  };
  const milestone: Required<Milestone> = {
    id: "m-id", key: "m1", name: "Kickoff", description: "d", estimatedDurationDays: 9,
    dependsOnMilestoneKeys: ["m0"], dependsOnMilestoneIds: ["m0-id"], requirements: [requirement], portalVisible: false,
  };
  const stage: Required<Stage> = {
    id: "s-id", key: "s1", name: "Docs", responsibleDepartmentId: "11111111-1111-1111-1111-111111111111",
    requiresApproval: true, autoAdvance: false, portalVisible: false, slaDays: 4, writeScope: "OWNER_ONLY",
    notificationTemplateKey: "tpl", entryCondition: { source: "ATTRIBUTE", key: "k", operator: "IN", value: "v", values: ["a"] },
    fallbackNextStageKey: "s2", fallbackNextStageId: "s2-id", milestones: [milestone],
    branchRules: [{ id: "b-id", condition: { source: "CUSTOMER", key: "seg", operator: "EQ", value: "X", values: ["x"] }, targetStageKey: "s3", targetStageId: "s3-id" }],
    pausesOnCustomer: false,
  };
  const attribute: Required<Attribute> = { id: "a-id", key: "a1", label: "L", dataType: "ENUM", required: true, allowedValues: ["x", "y"] };

  // Exhaustive over the REQUEST types: tsc fails when a request key is added without being listed here.
  const stageKeys: Record<keyof StageRequest, true> = {
    key: true, name: true, responsibleDepartmentId: true, requiresApproval: true, autoAdvance: true, portalVisible: true,
    slaDays: true, writeScope: true, notificationTemplateKey: true, entryCondition: true, fallbackNextStageKey: true,
    milestones: true, branchRules: true, pausesOnCustomer: true,
  };
  const milestoneKeys: Record<keyof MilestoneRequest, true> = {
    key: true, name: true, description: true, estimatedDurationDays: true, dependsOnMilestoneKeys: true,
    requirements: true, portalVisible: true,
  };
  const requirementKeys: Record<keyof RequirementRequest, true> = {
    kind: true, label: true, weight: true, mandatory: true, documentCategory: true, approverRelationship: true,
    requiresReview: true, agreementRecordMode: true, agreementName: true,
  };
  const attributeKeys: Record<keyof AttributeRequest, true> = {
    key: true, label: true, dataType: true, required: true, allowedValues: true,
  };
  // View-only fields the request type does not carry (ids and resolved references).
  const viewOnly = ["id", "fallbackNextStageId", "dependsOnMilestoneIds"];

  it("keeps every request field of a stage, through JSON, at its non-default value", () => {
    const body = JSON.parse(JSON.stringify(toStageDraft(stage)));
    for (const k of Object.keys(stageKeys)) {
      if (k === "milestones" || k === "branchRules") continue;
      expect(body[k], `stage.${k}`).toEqual((stage as Record<string, unknown>)[k]);
    }
    expect(body.branchRules).toEqual([{ condition: stage.branchRules[0]!.condition, targetStageKey: "s3" }]);
  });

  it("keeps every request field of a milestone and its requirement", () => {
    const body = JSON.parse(JSON.stringify(toMilestoneRequest(milestone)));
    for (const k of Object.keys(milestoneKeys)) {
      if (k === "requirements") continue;
      expect(body[k], `milestone.${k}`).toEqual((milestone as Record<string, unknown>)[k]);
    }
    for (const k of Object.keys(requirementKeys)) {
      expect(body.requirements[0][k], `requirement.${k}`).toEqual((requirement as Record<string, unknown>)[k]);
    }
  });

  it("keeps every request field of an attribute", () => {
    const body = JSON.parse(JSON.stringify(toAttributeDraft(attribute)));
    for (const k of Object.keys(attributeKeys)) {
      expect(body[k], `attribute.${k}`).toEqual((attribute as Record<string, unknown>)[k]);
    }
  });

  it("only leaves the declared view-only fields out of the draft", () => {
    const draft = toStageDraft(stage) as Record<string, unknown>;
    const dropped = Object.keys(stage).filter((k) => !(k in draft));
    expect(dropped.sort()).toEqual(["fallbackNextStageId", "id"].sort());
    expect(viewOnly).toEqual(expect.arrayContaining(dropped));
  });
});
