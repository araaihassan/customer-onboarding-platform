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
export type CloneTemplateRequest = components["schemas"]["CloneTemplateRequest"];
export type ProblemList = components["schemas"]["ProblemList"];
export type MigrationPreview = components["schemas"]["MigrationPreviewView"];
export type Candidate = components["schemas"]["CandidateView"];
export type MigrateRequest = components["schemas"]["MigrateRequest"];
export type MigrateResult = components["schemas"]["MigrateResultView"];

export const workflowKeys = {
  all: ["workflows"] as const,
  templates: () => [...workflowKeys.all, "templates"] as const,
  definition: (versionId: string) => [...workflowKeys.all, "definition", versionId] as const,
  template: (templateId: string) => [...workflowKeys.all, "template", templateId] as const,
};

export const migrationKeys = {
  preview: (versionId: string) => ["migration", "preview", versionId] as const,
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

/**
 * The single-template read (`WorkflowTemplateView`, carrying `customerId`) --
 * the only place a case's pinned template's customer-ownership (Q21) can be
 * read from, since neither `CaseView` nor `WorkflowDefinitionView` carries
 * it. Sub-project 3A Task 31 added this: both the version editor (gate 1
 * only applies to a customer-owned template's version) and the case
 * workspace (the Plan tab and the held-case banner only apply to a case on
 * one) need the same fact, and this is the one existing endpoint that has it.
 */
export function useWorkflowTemplate(templateId: string) {
  return useQuery({
    queryKey: workflowKeys.template(templateId),
    queryFn: () => apiFetch<WorkflowTemplate>(`/workflows/${templateId}`),
    enabled: Boolean(templateId),
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
 * Pulls the blocking draft's versionId out of a 409's body -- the same
 * best-effort JSON.parse shape parseProblems already uses for error bodies,
 * which are not generated types. Undefined if the body doesn't carry one (an
 * older backend, or a differently-shaped error).
 */
export function parseDraftVersionId(message: string): string | undefined {
  try {
    const body = JSON.parse(message) as { versionId?: string };
    return body.versionId;
  } catch {
    return undefined;
  }
}

/**
 * Deletes an unpublished draft and its whole graph -- WorkflowService.discardDraft.
 * The only way to unblock a template whose draft was abandoned without publishing:
 * WorkflowService.createDraft refuses a second draft outright, and nothing else
 * frees the slot.
 */
export function useDiscardDraft() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, versionId }: { templateId: string; versionId: string }) =>
      apiFetch<void>(`/workflows/${templateId}/versions/${versionId}/discard`, { method: "POST" }),
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
 * Clones a catalogue template for exactly one customer -- CustomerTemplateService.clone
 * (sub-project 3A Task 16, QA Q21). The response is the new customer template row
 * itself, and it deliberately carries no currentVersionId even though a DRAFT version
 * now exists under the hood: clone() returns that field null (WorkflowTemplateView's own
 * shape), the same way a template's very first draft always does. Opening it therefore
 * goes through the same createDraft 409-conflict "resume this draft" path every other
 * template's already-open draft already resolves through (parseDraftVersionId above) --
 * there is no versionId here to build a dedicated redirect from.
 */
export function useCloneTemplate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, body }: { templateId: string; body: CloneTemplateRequest }) =>
      apiFetch<WorkflowTemplate>(`/workflows/${templateId}/clone`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.templates() });
    },
  });
}

/**
 * Deep-copies the clone's catalogue source's current published version into a fresh
 * DRAFT of the SAME customer template -- CustomerTemplateService.refreshFromSource
 * (sub-project 3A Task 17, QA Q21 gate). Unlike clone above, the response IS the new
 * draft's own WorkflowDefinitionView (versionId included), because refresh always
 * targets an existing template row rather than creating one: the caller can route
 * straight into the editor rather than falling back to the 409-conflict resume path.
 */
export function useRefreshFromSource() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (templateId: string) =>
      apiFetch<WorkflowDefinition>(`/workflows/${templateId}/refresh`, { method: "POST" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: workflowKeys.templates() });
    },
  });
}

/**
 * versionId is the TARGET a case would move onto -- MigrationService.preview
 * counts every running case sitting on an OLDER version of the same
 * template, and how many of those are eligible to land here.
 */
export function useMigrationPreview(versionId: string | undefined) {
  return useQuery({
    queryKey: migrationKeys.preview(versionId ?? ""),
    queryFn: () => apiFetch<MigrationPreview>(`/cases/migration?versionId=${versionId}`),
    enabled: Boolean(versionId),
  });
}

/**
 * migrate() refuses the whole request rather than partially applying it, so a
 * 409 here means none of the requested cases moved -- never "some did."
 */
export function useMigrate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: MigrateRequest) =>
      apiFetch<MigrateResult>("/cases/migration", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (_result, { versionId }) => {
      void queryClient.invalidateQueries({ queryKey: migrationKeys.preview(versionId) });
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
