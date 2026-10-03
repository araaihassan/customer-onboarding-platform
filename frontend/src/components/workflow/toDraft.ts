import { newDraftKey, type AttributeDraft, type StageDraft } from "./draftState";
import type { Attribute, BranchRule, Milestone, Requirement, Stage } from "@/lib/api/workflows";

/** View -> draft mapping for the builder. Every StageView field the PUT accepts must be copied here, or a save silently erases it. */
export function toStageDraft(stage: Stage): StageDraft {
  return {
    key: stage.key ?? newDraftKey("stage"),
    name: stage.name,
    responsibleDepartmentId: stage.responsibleDepartmentId,
    requiresApproval: stage.requiresApproval,
    autoAdvance: stage.autoAdvance,
    pausesOnCustomer: stage.pausesOnCustomer,
    portalVisible: stage.portalVisible,
    slaDays: stage.slaDays,
    writeScope: stage.writeScope,
    notificationTemplateKey: stage.notificationTemplateKey,
    entryCondition: stage.entryCondition,
    fallbackNextStageKey: stage.fallbackNextStageKey,
    milestones: (stage.milestones ?? []).map(toMilestoneRequest),
    branchRules: (stage.branchRules ?? []).map(toBranchRuleRequest),
  };
}

function toMilestoneRequest(milestone: Milestone) {
  return {
    key: milestone.key ?? newDraftKey("milestone"),
    name: milestone.name,
    description: milestone.description,
    estimatedDurationDays: milestone.estimatedDurationDays,
    dependsOnMilestoneKeys: milestone.dependsOnMilestoneKeys ?? [],
    requirements: (milestone.requirements ?? []).map(toRequirementRequest),
  };
}

function toRequirementRequest(requirement: Requirement) {
  return {
    kind: requirement.kind,
    label: requirement.label,
    weight: requirement.weight,
    mandatory: requirement.mandatory,
    documentCategory: requirement.documentCategory,
    requiresReview: requirement.requiresReview,
    agreementRecordMode: requirement.agreementRecordMode,
    agreementName: requirement.agreementName,
    approverRelationship: requirement.approverRelationship,
  };
}

function toBranchRuleRequest(rule: BranchRule) {
  return { condition: rule.condition, targetStageKey: rule.targetStageKey };
}

export function toAttributeDraft(attribute: Attribute): AttributeDraft {
  return {
    key: attribute.key ?? newDraftKey("attribute"),
    label: attribute.label,
    dataType: attribute.dataType,
    required: attribute.required,
    allowedValues: attribute.allowedValues,
  };
}

