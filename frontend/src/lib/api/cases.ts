"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name — same discipline as lib/api/customers.ts and workflows.ts.
 */
export type Case = components["schemas"]["CaseView"];
export type CreateCaseRequest = components["schemas"]["CreateCaseRequest"];
export type UpdateCaseRequest = components["schemas"]["UpdateCaseRequest"];
export type AvailableTransition = components["schemas"]["AvailableTransitionView"];
export type Roadmap = components["schemas"]["RoadmapView"];
export type StageRoadmap = components["schemas"]["StageRoadmapView"];
export type MilestoneRoadmap = components["schemas"]["MilestoneRoadmapView"];
export type RequirementRoadmap = components["schemas"]["RequirementRoadmapView"];
export type Participant = components["schemas"]["ParticipantView"];
export type CaseStatus = NonNullable<Case["status"]>;
export type Approval = components["schemas"]["ApprovalView"];
export type ApprovalKind = NonNullable<Approval["kind"]>;
export type RequirementStatus = NonNullable<RequirementRoadmap["status"]>;
export type MilestoneStatus = NonNullable<MilestoneRoadmap["status"]>;
export type CaseRequirementView = components["schemas"]["CaseRequirementView"];
export type TimelineEvent = components["schemas"]["AuditEventView"];
export type TimelinePage = components["schemas"]["PageAuditEventView"];

export const TIMELINE_PAGE_SIZE = 25;

export const caseKeys = {
  all: ["cases"] as const,
  forCustomer: (customerId: string) => [...caseKeys.all, "customer", customerId] as const,
  detail: (caseId: string) => [...caseKeys.all, "detail", caseId] as const,
  roadmap: (caseId: string) => [...caseKeys.all, "roadmap", caseId] as const,
};

/**
 * The switcher's payload: every case for one customer, newest first
 * (CaseService.listForCustomer). `enabled` lets a caller without case.view
 * skip the request entirely, matching useContacts's own convention.
 */
export function useCases(customerId: string, enabled = true) {
  return useQuery({
    queryKey: caseKeys.forCustomer(customerId),
    queryFn: () => apiFetch<Case[]>(`/customers/${customerId}/cases`),
    enabled: enabled && Boolean(customerId),
  });
}

export function useCase(caseId: string) {
  return useQuery({
    queryKey: caseKeys.detail(caseId),
    queryFn: () => apiFetch<Case>(`/cases/${caseId}`),
    enabled: Boolean(caseId),
  });
}

/** Its own call so the header renders before the stage graph arrives (CaseController's own javadoc). */
export function useRoadmap(caseId: string) {
  return useQuery({
    queryKey: caseKeys.roadmap(caseId),
    queryFn: () => apiFetch<Roadmap>(`/cases/${caseId}/roadmap`),
    enabled: Boolean(caseId),
  });
}

/** Resolves a milestone or case owner id to a name -- the seam Task 26's CaseHeader/CustomerTable had no equivalent of for a customer's owner. */
export function useParticipants(caseId: string) {
  return useQuery({
    queryKey: [...caseKeys.detail(caseId), "participants"] as const,
    queryFn: () => apiFetch<Participant[]>(`/cases/${caseId}/participants`),
    enabled: Boolean(caseId),
  });
}

/** Every approval recorded against the case, of both kinds -- gated `case.view`, same as the roadmap. */
export function useApprovals(caseId: string) {
  return useQuery({
    queryKey: [...caseKeys.detail(caseId), "approvals"] as const,
    queryFn: () => apiFetch<Approval[]>(`/cases/${caseId}/approvals`),
    enabled: Boolean(caseId),
  });
}

/**
 * Every event recorded against the case, newest first, paginated -- the
 * Timeline tab's own source (uispecs §5e). `placeholderData` keeps the
 * current page on screen while the next one loads, matching useCustomers's
 * own paging behaviour, so paging never flashes a skeleton over a list the
 * reader is in the middle of reading.
 */
export function useTimeline(caseId: string, page = 0) {
  return useQuery({
    queryKey: [...caseKeys.detail(caseId), "timeline", page] as const,
    queryFn: () => apiFetch<TimelinePage>(`/cases/${caseId}/timeline?page=${page}&size=${TIMELINE_PAGE_SIZE}`),
    enabled: Boolean(caseId),
    placeholderData: (previous) => previous,
  });
}

export function useCreateCase() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCaseRequest) => apiFetch<Case>("/cases", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (created) => {
      if (created.customerId) void queryClient.invalidateQueries({ queryKey: caseKeys.forCustomer(created.customerId) });
    },
  });
}

function useCaseAction(path: (id: string) => string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => apiFetch<Case>(path(id), { method: "POST" }),
    onSuccess: (updated, id) => {
      queryClient.setQueryData(caseKeys.detail(id), updated);
      void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(id) });
    },
  });
}

/** The builder's "Advance" button binds to CaseView.availableTransition rather than recomputing exitability client-side. */
export function useAdvance() {
  return useCaseAction((id) => `/cases/${id}/advance`);
}

export function useResume() {
  return useCaseAction((id) => `/cases/${id}/resume`);
}

export function useHold() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      apiFetch<Case>(`/cases/${id}/hold`, { method: "POST", body: JSON.stringify({ reason }) }),
    onSuccess: (updated, { id }) => {
      queryClient.setQueryData(caseKeys.detail(id), updated);
      void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(id) });
    },
  });
}

function invalidateRoadmap(queryClient: ReturnType<typeof useQueryClient>, caseId: string) {
  void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(caseId) });
  void queryClient.invalidateQueries({ queryKey: caseKeys.detail(caseId) });
}

/**
 * ref/refType are the seam sub-projects 3-5 fill (SatisfyRequest's own
 * comment); both omitted is a plain manual check-off, the only kind this
 * sub-project's UI ever sends.
 */
export function useSatisfy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, requirementId, ref, refType }: {
      caseId: string;
      requirementId: string;
      ref?: string;
      refType?: string;
    }) =>
      apiFetch<CaseRequirementView>(
        `/cases/${caseId}/requirements/${requirementId}/satisfy`,
        { method: "POST", body: JSON.stringify(ref || refType ? { ref, refType } : {}) },
      ),
    onSuccess: (_result, { caseId }) => invalidateRoadmap(queryClient, caseId),
  });
}

export function useWaive() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, requirementId, reason }: { caseId: string; requirementId: string; reason: string }) =>
      apiFetch<CaseRequirementView>(
        `/cases/${caseId}/requirements/${requirementId}/waive`,
        { method: "POST", body: JSON.stringify({ reason }) },
      ),
    onSuccess: (_result, { caseId }) => invalidateRoadmap(queryClient, caseId),
  });
}

/** Requests Q5's forced completion -- returns the PENDING approval a decider (never the requester) later decides. */
export function useForceComplete() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, milestoneId, reason }: { caseId: string; milestoneId: string; reason: string }) =>
      apiFetch<Approval>(`/cases/${caseId}/milestones/${milestoneId}/force-complete`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: (_result, { caseId }) => invalidateRoadmap(queryClient, caseId),
  });
}

/** The rework path -- backward branches are refused, so reopening is this explicit action instead. */
export function useReopen() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, milestoneId, reason }: { caseId: string; milestoneId: string; reason: string }) =>
      apiFetch<void>(`/cases/${caseId}/milestones/${milestoneId}/reopen`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: (_result, { caseId }) => invalidateRoadmap(queryClient, caseId),
  });
}

/**
 * Two endpoints, not one -- ApprovalController's own javadoc: @RequirePermission
 * is static, so a single path could not carry approval.decide for a stage exit
 * and milestone.force_approve for a forcing without hiding the choice inside a
 * method body no coverage test can see. This hook is the one place that picks
 * between them, by the approval's own kind.
 */
export function useDecideApproval() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, approvalId, kind, approve, note }: {
      caseId: string;
      approvalId: string;
      kind: ApprovalKind;
      approve: boolean;
      note?: string;
    }) => {
      const segment = kind === "STAGE_EXIT" ? "stage-approvals" : "force-requests";
      return apiFetch<Approval>(`/cases/${caseId}/${segment}/${approvalId}/decide`, {
        method: "POST",
        body: JSON.stringify({ approve, note }),
      });
    },
    onSuccess: (_result, { caseId }) => invalidateRoadmap(queryClient, caseId),
  });
}

/**
 * Parses a ProblemDetail body's `detail` field -- the shape WriteScopeException
 * and SelfApprovalException are mapped to (RFC 7807), distinct from the
 * ProblemList shape validation errors use. Falls back to the raw message when
 * the body is not that shape, so a network failure still renders something.
 */
export function parseProblemDetail(message: string): string {
  try {
    const body = JSON.parse(message) as { detail?: string };
    if (body.detail) return body.detail;
  } catch {
    // fall through
  }
  return message;
}
