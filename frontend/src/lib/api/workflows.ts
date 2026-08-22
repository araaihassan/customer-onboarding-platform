"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name — same discipline as lib/api/customers.ts. A backend change must be a
 * compile error, not a runtime surprise.
 */
export type WorkflowTemplate = components["schemas"]["WorkflowTemplateView"];
export type CreateTemplateRequest = components["schemas"]["CreateTemplateRequest"];
export type WorkflowDefinition = components["schemas"]["WorkflowDefinitionView"];
export type WorkflowDefinitionRequest = components["schemas"]["WorkflowDefinitionRequest"];
export type Stage = components["schemas"]["StageView"];
export type StageRequest = components["schemas"]["StageRequest"];
export type Milestone = components["schemas"]["MilestoneView"];
export type MilestoneRequest = components["schemas"]["MilestoneRequest"];
export type Requirement = components["schemas"]["RequirementView"];
export type RequirementRequest = components["schemas"]["RequirementRequest"];
export type BranchRule = components["schemas"]["BranchRuleView"];
export type BranchRuleRequest = components["schemas"]["BranchRuleRequest"];
export type Condition = components["schemas"]["ConditionView"];
export type ConditionRequest = components["schemas"]["ConditionRequest"];
export type Attribute = components["schemas"]["AttributeView"];
export type AttributeRequest = components["schemas"]["AttributeRequest"];
export type ProblemList = components["schemas"]["ProblemList"];

export const workflowKeys = {
  all: ["workflows"] as const,
  templates: () => [...workflowKeys.all, "templates"] as const,
  definition: (versionId: string) => [...workflowKeys.all, "definition", versionId] as const,
};

export function useWorkflows() {
  return useQuery({
    queryKey: workflowKeys.templates(),
    queryFn: () => apiFetch<WorkflowTemplate[]>("/workflows"),
  });
}

/**
 * templateId is carried for a well-formed URL even though
 * WorkflowController.definition resolves purely from vid -- the id segment is
 * unused server-side, but a route that names the wrong template for a real
 * version id is a URL nobody should be able to construct from this hook.
 */
export function useDefinition(templateId: string, versionId: string) {
  return useQuery({
    queryKey: workflowKeys.definition(versionId),
    queryFn: () => apiFetch<WorkflowDefinition>(`/workflows/${templateId}/versions/${versionId}`),
    enabled: Boolean(templateId) && Boolean(versionId),
  });
}

export function useCreateTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateTemplateRequest) =>
      apiFetch<WorkflowTemplate>("/workflows", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.templates() });
    },
  });
}

/**
 * Empty, or a deep copy of the template's current published version --
 * WorkflowService.createDraft decides which server-side. Either way the
 * response is the new draft's own definition, ready to edit immediately.
 */
export function useCreateDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (templateId: string) =>
      apiFetch<WorkflowDefinition>(`/workflows/${templateId}/versions`, { method: "POST" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.templates() });
    },
  });
}

/**
 * The atomic whole-draft write: one PUT carries every stage, milestone,
 * requirement and attribute, matching WorkflowService.replaceDraft's own
 * full-replace contract. lockVersion is round-tripped from the last read so a
 * stale write answers 409, never silently overwrites a concurrent save.
 */
export function useSaveDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, versionId, body }: {
      templateId: string;
      versionId: string;
      body: WorkflowDefinitionRequest;
    }) =>
      apiFetch<WorkflowDefinition>(`/workflows/${templateId}/versions/${versionId}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: (definition, { versionId }) => {
      queryClient.setQueryData(workflowKeys.definition(versionId), definition);
    },
  });
}

/**
 * Publish is only ever called against a saved draft -- the screen disables the
 * button while dirty, so this never races the save it depends on.
 */
export function usePublish() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, versionId }: { templateId: string; versionId: string }) =>
      apiFetch<WorkflowDefinition>(`/workflows/${templateId}/versions/${versionId}/publish`, {
        method: "POST",
      }),
    onSuccess: (definition, { versionId }) => {
      queryClient.setQueryData(workflowKeys.definition(versionId), definition);
      void queryClient.invalidateQueries({ queryKey: workflowKeys.templates() });
    },
  });
}

/**
 * Parses a 422's body into its problem list, the same shape
 * JourneyExceptionHandler/WorkflowExceptionHandler both return. Falls back to
 * a single generic entry when the body is not that shape at all -- a network
 * failure or an unrelated error must still render something rather than throw
 * a second time inside the error path.
 */
export function parseProblems(message: string): string[] {
  try {
    const body = JSON.parse(message) as ProblemList;
    if (Array.isArray(body.problems) && body.problems.length > 0) return body.problems;
  } catch {
    // fall through
  }
  return [message || "common.error"];
}
