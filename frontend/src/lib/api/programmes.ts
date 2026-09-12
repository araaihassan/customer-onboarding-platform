"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/tasks.ts and lib/api/cases.ts.
 */
export type Programme = components["schemas"]["ProgrammeView"];
export type ProgrammeDetail = components["schemas"]["ProgrammeDetailView"];
export type ProgrammeJourney = components["schemas"]["ProgrammeJourneyView"];
export type CreateProgrammeRequest = components["schemas"]["CreateProgrammeRequest"];
export type UpdateProgrammeRequest = components["schemas"]["UpdateProgrammeRequest"];
export type AddProgrammeParticipantRequest = components["schemas"]["AddProgrammeParticipantRequest"];
export type ProgrammeStatus = NonNullable<Programme["status"]>;

export const programmeKeys = {
  all: ["programmes"] as const,
  forCustomer: (customerId: string) => [...programmeKeys.all, "customer", customerId] as const,
  detail: (id: string) => [...programmeKeys.all, "detail", id] as const,
};

/**
 * A customer's programmes, newest first (ProgrammeController.listForCustomer,
 * Task 27.6) -- the only listing endpoint this module has; there is no
 * tenant-wide "all programmes" read, so a caller with no customer id yet gets
 * a disabled query rather than a request that has nowhere to point,
 * matching useCases's own `enabled` convention in cases.ts.
 */
export function useProgrammes(customerId?: string) {
  return useQuery({
    queryKey: customerId ? programmeKeys.forCustomer(customerId) : programmeKeys.all,
    queryFn: () => apiFetch<Programme[]>(`/customers/${customerId}/programmes`),
    enabled: Boolean(customerId),
  });
}

/** The programme container: its own fields, its visible journeys, and the duration-weighted rollup over them (Q20). */
export function useProgramme(id: string) {
  return useQuery({
    queryKey: programmeKeys.detail(id),
    queryFn: () => apiFetch<ProgrammeDetail>(`/programmes/${id}`),
    enabled: Boolean(id),
  });
}

export function useCreateProgramme() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateProgrammeRequest) =>
      apiFetch<Programme>("/programmes", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (created) => {
      if (created.customerId) {
        void queryClient.invalidateQueries({ queryKey: programmeKeys.forCustomer(created.customerId) });
      }
    },
  });
}

/** A full-replace edit -- name, description, owner/department/team. `UpdateProgrammeRequest` carries no `customerId` (it never moves programmes between customers), so the response's own `customerId` is what's used to invalidate the customer's list. */
export function useUpdateProgramme() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateProgrammeRequest }) =>
      apiFetch<Programme>(`/programmes/${id}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: (updated, { id }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(id) });
      if (updated.customerId) {
        void queryClient.invalidateQueries({ queryKey: programmeKeys.forCustomer(updated.customerId) });
      }
    },
  });
}

/**
 * Deactivation, never deletion -- the 204 body carries nothing back (Task
 * 27.6's own doc comment: "status set to INACTIVE, never deleted"), so the
 * customer id needed to invalidate that customer's programme list has to
 * come from the caller, the same shape `useDeactivateProgramme`'s sibling
 * writes below take.
 */
export function useDeactivateProgramme() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id }: { id: string; customerId?: string }) =>
      apiFetch<void>(`/programmes/${id}/deactivate`, { method: "POST" }),
    onSuccess: (_result, { id, customerId }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(id) });
      if (customerId) {
        void queryClient.invalidateQueries({ queryKey: programmeKeys.forCustomer(customerId) });
      }
    },
  });
}

/**
 * Links a case into the programme container (Q20). Invalidates the
 * programme's own detail (its journeys/rollup changed) AND the whole case
 * key family, not just this one case's detail -- a linked case's own view
 * may start rendering programme context once it has one, and there is no
 * narrower case key here to target than `caseKeys.all`.
 */
export function useAddJourney() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ programmeId, caseId }: { programmeId: string; caseId: string }) =>
      apiFetch<void>(`/programmes/${programmeId}/journeys`, {
        method: "POST",
        body: JSON.stringify({ caseId }),
      }),
    onSuccess: (_result, { programmeId }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(programmeId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.all });
    },
  });
}

/** Unlinks a case (removed_at set, never deleted) -- same invalidation shape as `useAddJourney`. */
export function useRemoveJourney() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ programmeId, caseId }: { programmeId: string; caseId: string }) =>
      apiFetch<void>(`/programmes/${programmeId}/journeys/${caseId}/remove`, { method: "POST" }),
    onSuccess: (_result, { programmeId }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(programmeId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.all });
    },
  });
}

/** Adds a read-only (or, with `alsoGrantJourneyAccess`, case-scoped) participant -- design spec §6.3. */
export function useAddProgrammeParticipant() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ programmeId, ...body }: { programmeId: string } & AddProgrammeParticipantRequest) =>
      apiFetch<void>(`/programmes/${programmeId}/participants`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_result, { programmeId }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(programmeId) });
    },
  });
}

/** Removes a participant (status set to REMOVED, never deleted). */
export function useRemoveProgrammeParticipant() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ programmeId, userId }: { programmeId: string; userId: string }) =>
      apiFetch<void>(`/programmes/${programmeId}/participants/${userId}/remove`, { method: "POST" }),
    onSuccess: (_result, { programmeId }) => {
      void queryClient.invalidateQueries({ queryKey: programmeKeys.detail(programmeId) });
    },
  });
}
