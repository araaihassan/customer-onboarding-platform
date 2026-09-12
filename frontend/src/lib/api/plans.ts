"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/tasks.ts and lib/api/cases.ts. Q22/Q23's
 * two gates: shape approval lives on a workflow template version
 * (`PlanShapeApprovalView`), schedule approval lives per journey/case
 * (`PlanRevisionView`) -- two distinct resources, hence two key families
 * below rather than one.
 */
export type PlanShapeApproval = components["schemas"]["PlanShapeApprovalView"];
export type DecidePlanRequest = components["schemas"]["DecidePlanRequest"];
export type PlanRevision = components["schemas"]["PlanRevisionView"];
export type PlanRevisionItem = components["schemas"]["PlanRevisionItemView"];
export type IssueRevisionRequest = components["schemas"]["IssueRevisionRequest"];
export type PlanRevisionDiff = components["schemas"]["PlanRevisionDiffView"];
export type PlanRevisionDiffRow = components["schemas"]["PlanRevisionDiffRowView"];
export type PlanRevisionStatus = NonNullable<PlanRevision["status"]>;
export type PlanShape = components["schemas"]["PlanShapeView"];
export type PlanShapeStage = components["schemas"]["PlanShapeStageView"];
export type PlanShapeMilestone = components["schemas"]["PlanShapeMilestoneView"];

export const shapeApprovalKeys = {
  all: ["shape-approval"] as const,
  detail: (templateId: string, versionId: string) => [...shapeApprovalKeys.all, templateId, versionId] as const,
};

export const planShapeKeys = {
  all: ["plan-shape"] as const,
  detail: (templateId: string, versionId: string) => [...planShapeKeys.all, templateId, versionId] as const,
};

export const planRevisionKeys = {
  all: ["plan-revisions"] as const,
  forCase: (caseId: string) => [...planRevisionKeys.all, "case", caseId] as const,
  detail: (caseId: string, revisionId: string) => [...planRevisionKeys.all, "detail", caseId, revisionId] as const,
  diff: (caseId: string, revisionId: string, againstId: string) =>
    [...planRevisionKeys.detail(caseId, revisionId), "diff", againstId] as const,
};

/** Gate 1's own state -- the latest shape approval row for one template version (WorkflowController's own javadoc). */
export function useShapeApproval(templateId: string, versionId: string) {
  return useQuery({
    queryKey: shapeApprovalKeys.detail(templateId, versionId),
    queryFn: () => apiFetch<PlanShapeApproval>(`/workflows/${templateId}/versions/${versionId}/shape-approval`),
    enabled: Boolean(templateId) && Boolean(versionId),
  });
}

/** Submits a customer-tailored version for gate 1 -- no request body (WorkflowController's own operation carries none); refused for a still-draft version or a catalogue (customer-less) template. */
export function useSubmitShape() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, versionId }: { templateId: string; versionId: string }) =>
      apiFetch<PlanShapeApproval>(`/workflows/${templateId}/versions/${versionId}/shape-approval`, {
        method: "POST",
      }),
    onSuccess: (_result, { templateId, versionId }) => {
      void queryClient.invalidateQueries({ queryKey: shapeApprovalKeys.detail(templateId, versionId) });
      void queryClient.invalidateQueries({ queryKey: planShapeKeys.detail(templateId, versionId) });
    },
  });
}

/** Decides a submitted gate-1 approval -- one-shot, refused once a decision already exists. */
export function useDecideShape() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ templateId, versionId, ...body }: { templateId: string; versionId: string } & DecidePlanRequest) =>
      apiFetch<PlanShapeApproval>(`/workflows/${templateId}/versions/${versionId}/shape-approval/decision`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_result, { templateId, versionId }) => {
      void queryClient.invalidateQueries({ queryKey: shapeApprovalKeys.detail(templateId, versionId) });
      void queryClient.invalidateQueries({ queryKey: planShapeKeys.detail(templateId, versionId) });
    },
  });
}

/**
 * Task 21's combined read: the portal-visible rendering (stages/milestones,
 * already filtered server-side by both a stage's and a milestone's own
 * `portalVisible`) alongside gate 1's current approval, in one call --
 * `WorkflowController`'s own doc: "The portal-visible shape and its current
 * approval state". Deliberately used instead of a second `useShapeApproval`
 * fetch for `ShapeApprovalPanel`'s own rendering: that endpoint has no 404
 * for "never submitted" (unlike `/shape-approval`), so `approval` is simply
 * `undefined` rather than requiring 404-as-empty-state handling here.
 */
export function usePlanShape(templateId: string, versionId: string) {
  return useQuery({
    queryKey: planShapeKeys.detail(templateId, versionId),
    queryFn: () => apiFetch<PlanShape>(`/workflows/${templateId}/versions/${versionId}/plan`),
    enabled: Boolean(templateId) && Boolean(versionId),
  });
}

/** Every schedule revision ever issued for a case, newest first (gate 2). */
export function usePlanRevisions(caseId: string) {
  return useQuery({
    queryKey: planRevisionKeys.forCase(caseId),
    queryFn: () => apiFetch<PlanRevision[]>(`/cases/${caseId}/plan-revisions`),
    enabled: Boolean(caseId),
  });
}

/** Issues a new revision, superseding any outstanding one for the case -- refused (422) until gate 1 (shape) has been approved (PlanRevisionController's own javadoc). */
export function useIssueRevision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, ...body }: { caseId: string } & IssueRevisionRequest) =>
      apiFetch<PlanRevision>(`/cases/${caseId}/plan-revisions`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_result, { caseId }) => {
      void queryClient.invalidateQueries({ queryKey: planRevisionKeys.forCase(caseId) });
    },
  });
}

/**
 * Decides gate 2. Approving a case's first-ever revision releases its hold
 * (Q22/Q23 -- PlanRevisionController's own javadoc on this response), so this
 * invalidates the case's own detail and roadmap alongside the revision list
 * itself -- the same "a write here can move state elsewhere" shape
 * `useChangeTaskStatus` (tasks.ts) already follows for a requirement-linked
 * task completing.
 */
export function useDecideRevision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, revisionId, ...body }: { caseId: string; revisionId: string } & DecidePlanRequest) =>
      apiFetch<PlanRevision>(`/cases/${caseId}/plan-revisions/${revisionId}/decision`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_result, { caseId }) => {
      void queryClient.invalidateQueries({ queryKey: planRevisionKeys.forCase(caseId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.detail(caseId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(caseId) });
    },
  });
}

/**
 * Every milestone's change between two revisions, computed server-side
 * (PlanRevisionController's own javadoc). The brief names this hook
 * `useRevisionDiff(revisionId, againstId)`, but the endpoint itself is
 * `GET /cases/{caseId}/plan-revisions/{revisionId}/diff?against={against}` --
 * `caseId` is a required path segment, not an optional extra, so it is taken
 * here as this hook's first argument rather than omitted.
 */
export function useRevisionDiff(caseId: string, revisionId: string, againstId: string) {
  return useQuery({
    queryKey: planRevisionKeys.diff(caseId, revisionId, againstId),
    queryFn: () =>
      apiFetch<PlanRevisionDiff>(
        `/cases/${caseId}/plan-revisions/${revisionId}/diff?against=${encodeURIComponent(againstId)}`,
      ),
    enabled: Boolean(caseId) && Boolean(revisionId) && Boolean(againstId),
  });
}
