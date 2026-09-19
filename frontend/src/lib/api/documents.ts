"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/tasks.ts and cases.ts. Never hand-write
 * an API type.
 */
export type Document = components["schemas"]["DocumentView"];
export type DocumentVersion = components["schemas"]["DocumentVersionView"];
export type DocumentPage = components["schemas"]["PageDocumentView"];
export type DocumentCategory = NonNullable<Document["category"]>;
export type DocumentStatus = NonNullable<Document["status"]>;
export type DocumentVisibilityTier = NonNullable<Document["visibilityTier"]>;

/** The `metadata` part of an upload's multipart body -- DocumentController.upload's own @RequestPart type. */
export type CreateDocumentMetadata = components["schemas"]["CreateDocumentRequest"];
export type PatchDocumentRequest = components["schemas"]["PatchDocumentRequest"];
export type ShareDocumentRequest = components["schemas"]["ShareDocumentRequest"];
export type DocumentShare = components["schemas"]["DocumentShareView"];
export type DocumentCaseLink = components["schemas"]["DocumentCaseLinkView"];
export type ReviewVersionRequest = components["schemas"]["ReviewVersionRequest"];

export type DocumentRequest = components["schemas"]["DocumentRequestView"];
export type CreateDocumentRequestRequest = components["schemas"]["CreateDocumentRequestRequest"];
export type DocumentRequestStatus = NonNullable<DocumentRequest["status"]>;

export const DOCUMENTS_PAGE_SIZE = 25;

export const documentKeys = {
  all: ["documents"] as const,
  /** The tenant-wide index (`useDocuments`) -- its own family root, not `all` itself, so invalidating it doesn't also touch every case's own listing or every detail query. */
  index: () => [...documentKeys.all, "index"] as const,
  forCase: (caseId: string) => [...documentKeys.all, "case", caseId] as const,
  detail: (id: string) => [...documentKeys.all, "detail", id] as const,
  requestsForCase: (caseId: string) => [...documentKeys.all, "requests", "case", caseId] as const,
  /**
   * The pending-review queue -- no read hook in this task's own scope reads
   * this key yet (there is no `GET` endpoint for it today; a real,
   * pre-existing gap, not this task's to close), but `useReviewVersion` must
   * invalidate it regardless (the brief's own explicit requirement), since a
   * review made from anywhere changes what belongs in that queue. Established
   * here, correctly, so the eventual pending-queue read hook only has to key
   * its own query off this same value rather than also having to remember to
   * wire this invalidation in after the fact.
   */
  pending: () => [...documentKeys.all, "pending"] as const,
};

/** Every document visible to the caller, tenant-wide, scope + audience filtered (DocumentController.list's own doc comment). */
export function useDocuments(page = 0) {
  return useQuery({
    queryKey: [...documentKeys.index(), page] as const,
    queryFn: () => apiFetch<DocumentPage>(`/documents?page=${page}&size=${DOCUMENTS_PAGE_SIZE}`),
    placeholderData: (previous) => previous,
  });
}

/** Documents whose home is this case, or a live link into it (DocumentService.forCase's own "home-or-linked" filter). */
export function useCaseDocuments(caseId: string, page = 0) {
  return useQuery({
    queryKey: [...documentKeys.forCase(caseId), page] as const,
    queryFn: () => apiFetch<DocumentPage>(`/cases/${caseId}/documents?page=${page}&size=${DOCUMENTS_PAGE_SIZE}`),
    enabled: Boolean(caseId),
    placeholderData: (previous) => previous,
  });
}

export function useDocument(id: string) {
  return useQuery({
    queryKey: documentKeys.detail(id),
    queryFn: () => apiFetch<Document>(`/documents/${id}`),
    enabled: Boolean(id),
  });
}

/**
 * Uploads a new document under a case -- multipart/form-data, two parts:
 * `file` (binary) and `metadata` (the `CreateDocumentRequest` JSON body).
 * `metadata` is appended as a `Blob` with an explicit `application/json`
 * type, not a plain string (which a browser sends as `text/plain` with no
 * content-type override available) -- `DocumentController.upload`'s
 * `@RequestPart("metadata") CreateDocumentRequest metadata` resolves its
 * converter from the part's own declared Content-Type, so an explicit
 * `application/json` is what guarantees Jackson's converter is picked, rather
 * than relying on undocumented fallback behaviour for a part with none.
 *
 * Invalidates both the case's own listing and the tenant-wide index: a newly
 * uploaded document changes what both reads return.
 */
export function useUploadDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, file, metadata }: { caseId: string; file: File; metadata: CreateDocumentMetadata }) => {
      const body = new FormData();
      body.append("file", file);
      body.append("metadata", new Blob([JSON.stringify(metadata)], { type: "application/json" }));
      return apiFetch<Document>(`/cases/${caseId}/documents`, { method: "POST", body });
    },
    onSuccess: (_created, { caseId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(caseId) });
      void queryClient.invalidateQueries({ queryKey: documentKeys.index() });
    },
  });
}

/**
 * Appends a new version -- multipart/form-data, `file` only (no `metadata`
 * part, unlike `useUploadDocument`: `DocumentController.addVersion` takes no
 * second `@RequestPart` at all). `DocumentVersionView` carries no `caseId` of
 * its own, so the caller supplies it for the case-listing invalidation below
 * -- the same shape `useCreateTask` already takes `caseId` as an explicit
 * mutation variable rather than reading it off a response that doesn't carry it.
 *
 * Invalidates the document's own detail query (`currentVersionId` changed)
 * and the case's listing (a version count/status may render there).
 */
export function useAddVersion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, file }: { documentId: string; caseId: string; file: File }) => {
      const body = new FormData();
      body.append("file", file);
      return apiFetch<DocumentVersion>(`/documents/${documentId}/versions`, { method: "POST", body });
    },
    onSuccess: (_version, { documentId, caseId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
      void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(caseId) });
    },
  });
}

/** A full-replace edit of name/category/target -- invalidates the document's own detail and the case's listing (retargeting changes how it renders in both). */
export function usePatchDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: PatchDocumentRequest }) =>
      apiFetch<Document>(`/documents/${id}`, { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: (updated, { id }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(id) });
      if (updated.caseId) void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(updated.caseId) });
    },
  });
}

/** A status column, never a DELETE -- same invalidation shape as usePatchDocument. */
export function useRetireDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      apiFetch<Document>(`/documents/${id}/retire`, { method: "POST", body: JSON.stringify({ reason }) }),
    onSuccess: (updated, { id }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(id) });
      if (updated.caseId) void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(updated.caseId) });
    },
  });
}

/**
 * Grants a share to a contact/user/department. Invalidates only the
 * document's own detail query -- there is no `useDocumentShares` list hook in
 * this task's scope (YAGNI: the brief's own Interfaces line does not name
 * one, and nothing here consumes it yet), so there is no second query to go
 * stale.
 */
export function useShareDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, body }: { documentId: string; body: ShareDocumentRequest }) =>
      apiFetch<DocumentShare>(`/documents/${documentId}/shares`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (_share, { documentId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
    },
  });
}

/** Revokes a share -- `revoked_at` set, never deleted; same single invalidation as useShareDocument. */
export function useRevokeShare() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, shareId }: { documentId: string; shareId: string }) =>
      apiFetch<DocumentShare>(`/documents/${documentId}/shares/${shareId}`, { method: "DELETE" }),
    onSuccess: (_share, { documentId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
    },
  });
}

/**
 * Links a document into a second case. `DocumentService.forCase`'s own
 * "home-or-linked" filter (its own javadoc names this) means the document's
 * HOME case listing is structurally unaffected by a link -- it already shows
 * the document -- so only the newly-linked case's own listing needs to go
 * stale, alongside the document's own detail query.
 */
export function useLinkDocument() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, caseId }: { documentId: string; caseId: string }) =>
      apiFetch<DocumentCaseLink>(`/documents/${documentId}/links`, {
        method: "POST",
        body: JSON.stringify({ caseId }),
      }),
    onSuccess: (_link, { documentId, caseId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
      void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(caseId) });
    },
  });
}

/**
 * Every document request open against one case.
 *
 * **A real, pre-existing backend gap, not a frontend defect**: there is no
 * `GET /cases/{caseId}/document-requests` endpoint today.
 * `DocumentRequestController` (backend/src/main/java/co/ara/onboarding/document/DocumentRequestController.java)
 * maps only `POST /cases/{caseId}/document-requests` (create),
 * `POST /document-requests/{id}/withdraw` and `POST /document-requests/{id}/fulfil`
 * -- no `@GetMapping` at all -- and `DocumentRequestService` has no
 * `list`/`forCase` method for a controller to call even if one existed.
 * Confirmed by reading both files directly, not inferred from `generated.ts`
 * being incomplete. This hook is written to the shape Task 30's own brief and
 * the plan's Interfaces line (`docs/superpowers/plans/2026-09-12-documents.md`,
 * Task 30) specify, matching the case-scoped path a symmetrical backend
 * endpoint would need, so a future backend task only has to add the endpoint
 * -- not also come back here to add the hook. Until that endpoint exists,
 * calling this hook against a live backend 404s; every test below exercises
 * it against a mocked `fetch`, which cannot detect that gap, only the hook's
 * own client-side behaviour once a matching endpoint exists.
 */
export function useDocumentRequests(caseId: string) {
  return useQuery({
    queryKey: documentKeys.requestsForCase(caseId),
    queryFn: () => apiFetch<DocumentRequest[]>(`/cases/${caseId}/document-requests`),
    enabled: Boolean(caseId),
  });
}

/** An ad-hoc request for a customer contact to supply a document -- requirementId is always null on this path. */
export function useCreateDocumentRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, ...body }: { caseId: string } & CreateDocumentRequestRequest) =>
      apiFetch<DocumentRequest>(`/cases/${caseId}/document-requests`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (_created, { caseId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.requestsForCase(caseId) });
    },
  });
}

/** A status column, never a DELETE -- a WITHDRAWN request never satisfies its requirement. */
export function useWithdrawRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) =>
      apiFetch<DocumentRequest>(`/document-requests/${id}/withdraw`, {
        method: "POST",
        body: JSON.stringify({ reason }),
      }),
    onSuccess: (updated) => {
      if (updated.caseId) void queryClient.invalidateQueries({ queryKey: documentKeys.requestsForCase(updated.caseId) });
    },
  });
}

/**
 * Fulfils an open request with an existing document. Invalidates the case's
 * request listing, its document listing (the fulfilling document may now
 * render differently there), and its roadmap -- the identical shape
 * `useChangeTaskStatus` (tasks.ts) already uses, since fulfilling a
 * requirement-linked request that does not require review satisfies that
 * requirement immediately and can complete a milestone.
 */
export function useFulfilRequest() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, documentId }: { id: string; documentId: string }) =>
      apiFetch<DocumentRequest>(`/document-requests/${id}/fulfil`, {
        method: "POST",
        body: JSON.stringify({ documentId }),
      }),
    onSuccess: (updated) => {
      if (!updated.caseId) return;
      void queryClient.invalidateQueries({ queryKey: documentKeys.requestsForCase(updated.caseId) });
      void queryClient.invalidateQueries({ queryKey: documentKeys.forCase(updated.caseId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(updated.caseId) });
    },
  });
}

/**
 * Approves or rejects a pending version. Invalidates the document's own
 * detail query (`reviewStatus`/`reviewedBy`/`reviewedAt` all live on the
 * version, read through the document) AND the pending-review queue key
 * (`documentKeys.pending()`) -- explicitly called out in the brief: a review
 * made anywhere must not leave a since-decided version still showing in a
 * pending list. No hook in this task's own scope reads that key yet (see
 * `documentKeys.pending`'s own comment); the invalidation is wired in now so
 * the eventual read hook does not also have to come back here to add it.
 */
export function useReviewVersion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ documentId, versionNo, ...body }: { documentId: string; versionNo: number } & ReviewVersionRequest) =>
      apiFetch<DocumentVersion>(`/documents/${documentId}/versions/${versionNo}/review`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: (_reviewed, { documentId }) => {
      void queryClient.invalidateQueries({ queryKey: documentKeys.detail(documentId) });
      void queryClient.invalidateQueries({ queryKey: documentKeys.pending() });
    },
  });
}
