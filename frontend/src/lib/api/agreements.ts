"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/** Generated types only -- never hand-write an API type (lib/api/documents.ts' discipline). */
export type Agreement = components["schemas"]["AgreementView"];
export type AgreementDetail = components["schemas"]["AgreementDetailView"];
export type AgreementSignatory = components["schemas"]["AgreementSignatoryView"];
export type AgreementVersion = components["schemas"]["AgreementVersionView"];
export type AgreementSignature = components["schemas"]["AgreementSignatureView"];
export type AgreementSummary = components["schemas"]["AgreementSummaryView"];
export type AgreementPage = components["schemas"]["PageAgreementView"];
export type PortalAgreement = components["schemas"]["PortalAgreementView"];
export type AgreementStatus = NonNullable<Agreement["status"]>;
export type AgreementDisplayStatus = NonNullable<Agreement["displayStatus"]>;
export type AgreementRecordMode = NonNullable<Agreement["recordMode"]>;
export type PatchAgreementRequest = components["schemas"]["PatchAgreementRequest"];
export type SignatoryRequest = components["schemas"]["SignatoryRequest"];
export type ReviewAgreementRequest = components["schemas"]["ReviewAgreementRequest"];
export type RecordSignatureRequest = components["schemas"]["RecordSignatureRequest"];

/** Mirrors agreement_mode_ck / AgreementRecordMode.java. Shared by the builder and the tab so the two never drift. */
export const AGREEMENT_RECORD_MODES: AgreementRecordMode[] = ["FILE_BACKED", "STRUCTURED_PLUS_FILE", "STRUCTURED_ONLY"];
export const recordModeIncludesFile = (m: AgreementRecordMode) => m !== "STRUCTURED_ONLY";

export const AGREEMENTS_PAGE_SIZE = 25;

export const agreementKeys = {
  all: ["agreements"] as const,
  index: () => [...agreementKeys.all, "index"] as const,
  summary: () => [...agreementKeys.all, "summary"] as const,
  forCase: (caseId: string) => [...agreementKeys.all, "case", caseId] as const,
  detail: (id: string) => [...agreementKeys.all, "detail", id] as const,
};

export function useAgreements(status?: AgreementDisplayStatus, page = 0) {
  return useQuery({
    queryKey: [...agreementKeys.index(), status ?? "all", page] as const,
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(AGREEMENTS_PAGE_SIZE) });
      if (status) params.set("status", status);
      return apiFetch<AgreementPage>(`/agreements?${params.toString()}`);
    },
    placeholderData: (previous) => previous,
  });
}

export function useAgreementSummary() {
  return useQuery({ queryKey: agreementKeys.summary(), queryFn: () => apiFetch<AgreementSummary>("/agreements/summary") });
}

export function useCaseAgreements(caseId: string) {
  return useQuery({
    queryKey: agreementKeys.forCase(caseId),
    queryFn: () => apiFetch<Agreement[]>(`/cases/${caseId}/agreements`),
    enabled: Boolean(caseId),
  });
}

export function useAgreement(id: string) {
  return useQuery({
    queryKey: agreementKeys.detail(id),
    queryFn: () => apiFetch<AgreementDetail>(`/agreements/${id}`),
    enabled: Boolean(id),
  });
}

/**
 * Every agreement write changes the detail, the case's list, the index and the summary
 * counts. A signature that satisfies a requirement also moves the roadmap and progress,
 * so the case itself is invalidated too (`touchesCase`).
 *
 * `extraDetailIds` names agreements whose own detail went stale but who are not the one the
 * response describes: cancel returns the SUCCESSOR draft, so the cancelled agreement's detail
 * (now CANCELLED) must be invalidated by the id the caller passed in.
 */
function useAgreementMutation<TVars>(
  fn: (vars: TVars) => Promise<AgreementDetail>,
  opts: { touchesCase?: boolean; extraDetailIds?: (vars: TVars) => string[] } = {},
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: (detail, vars) => {
      const a = detail.agreement;
      const ids = new Set(opts.extraDetailIds?.(vars) ?? []);
      if (a?.id) ids.add(a.id);
      for (const id of ids) void queryClient.invalidateQueries({ queryKey: agreementKeys.detail(id) });
      if (a?.caseId) void queryClient.invalidateQueries({ queryKey: agreementKeys.forCase(a.caseId) });
      void queryClient.invalidateQueries({ queryKey: agreementKeys.index() });
      void queryClient.invalidateQueries({ queryKey: agreementKeys.summary() });
      if (opts.touchesCase && a?.caseId) {
        void queryClient.invalidateQueries({ queryKey: caseKeys.detail(a.caseId) });
        void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(a.caseId) });
      }
    },
  });
}

// apiFetch defaults Content-Type to application/json for a non-FormData body and strips it for
// FormData, so a JSON body is just JSON.stringify and a multipart body is a bare FormData.

export const usePatchAgreement = () =>
  useAgreementMutation(({ id, body }: { id: string; body: PatchAgreementRequest }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
  );

export const useReplaceSignatories = () =>
  useAgreementMutation(
    ({ id, signatories, lockVersion }: { id: string; signatories: SignatoryRequest[]; lockVersion: number }) =>
      apiFetch<AgreementDetail>(`/agreements/${id}/signatories`, {
        method: "PUT",
        body: JSON.stringify({ signatories, lockVersion }),
      }),
  );

export const useUploadAgreementFile = () =>
  useAgreementMutation(({ id, file, lockVersion }: { id: string; file: File; lockVersion: number }) => {
    const body = new FormData();
    body.append("file", file);
    return apiFetch<AgreementDetail>(`/agreements/${id}/file?lockVersion=${lockVersion}`, { method: "POST", body });
  });

export const useSubmitAgreement = () =>
  useAgreementMutation(({ id, lockVersion }: { id: string; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/submit`, { method: "POST", body: JSON.stringify({ lockVersion }) }),
  );

/** `body.lockVersion` is the agreement's; the controller reads it from ReviewAgreementRequest. */
export const useReviewAgreement = () =>
  useAgreementMutation(({ id, versionNumber, body }: { id: string; versionNumber: number; body: ReviewAgreementRequest }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/versions/${versionNumber}/review`, {
      method: "POST",
      body: JSON.stringify(body),
    }),
  );

export const useSendAgreement = () =>
  useAgreementMutation(({ id, lockVersion }: { id: string; lockVersion: number }) =>
    apiFetch<AgreementDetail>(`/agreements/${id}/send`, { method: "POST", body: JSON.stringify({ lockVersion }) }),
  );

/**
 * Multipart: a JSON `signature` part (explicit application/json Blob, same reason as
 * useUploadDocument's `metadata`) plus an optional `file` part -- AgreementController.recordSignature's
 * own @RequestPart names. `signature.lockVersion` rides in the JSON part.
 */
export const useRecordSignature = () =>
  useAgreementMutation(
    ({ id, signature, file }: { id: string; signature: RecordSignatureRequest; file?: File }) => {
      const body = new FormData();
      body.append("signature", new Blob([JSON.stringify(signature)], { type: "application/json" }));
      if (file) body.append("file", file);
      return apiFetch<AgreementDetail>(`/agreements/${id}/signatures`, { method: "POST", body });
    },
    { touchesCase: true },
  );

/** Returns the SUCCESSOR draft; the cancelled agreement's own detail is invalidated by the id passed in. */
export const useCancelAgreement = () =>
  useAgreementMutation(
    ({ id, reason, lockVersion }: { id: string; reason: string; lockVersion: number }) =>
      apiFetch<AgreementDetail>(`/agreements/${id}/cancel`, {
        method: "POST",
        body: JSON.stringify({ reason, lockVersion }),
      }),
    { extraDetailIds: ({ id }) => [id] },
  );
