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
