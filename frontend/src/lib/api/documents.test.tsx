import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys } from "./cases";
import {
  documentKeys,
  useAddVersion,
  useCaseDocuments,
  useCreateDocumentRequest,
  useDocument,
  useDocumentRequests,
  useDocuments,
  useFulfilRequest,
  useLinkDocument,
  usePatchDocument,
  useRetireDocument,
  useRevokeShare,
  useReviewVersion,
  useShareDocument,
  useUploadDocument,
  useWithdrawRequest,
} from "./documents";

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper(client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })) {
  return {
    client,
    Wrapper: function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    },
  };
}

function lastUrl(): string {
  return fetchMock.mock.calls.at(-1)![0] as string;
}

function lastInit(): RequestInit {
  return fetchMock.mock.calls.at(-1)![1] as RequestInit;
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("useDocuments", () => {
  it("reads the tenant-wide index, paginated", async () => {
    fetchMock.mockResolvedValue(reply({ content: [{ id: "d-1" }] }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocuments(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/documents?page=0&size=25");
  });

  it("pages", async () => {
    fetchMock.mockResolvedValue(reply({ content: [] }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocuments(2), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/documents?page=2&size=25");
  });
});

describe("useCaseDocuments", () => {
  it("lists a case's documents, home or linked", async () => {
    fetchMock.mockResolvedValue(reply({ content: [{ id: "d-1", caseId: "c-1" }] }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useCaseDocuments("c-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/documents?page=0&size=25");
  });

  it("does not fire without a case id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useCaseDocuments(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useDocument", () => {
  it("reads one document by id", async () => {
    fetchMock.mockResolvedValue(reply({ id: "d-1" }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocument("d-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1");
  });

  it("does not fire without an id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocument(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useUploadDocument", () => {
  it("uploads with a multipart file part and a JSON metadata part, and invalidates the case listing AND the tenant-wide index", async () => {
    fetchMock.mockResolvedValue(reply({ id: "d-9", caseId: "c-1" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useUploadDocument(), { wrapper: Wrapper });

    const file = new File(["hello"], "hello.pdf", { type: "application/pdf" });
    await result.current.mutateAsync({
      caseId: "c-1",
      file,
      metadata: { name: "Hello", category: "OTHER", visibilityTier: "COMPANY_SHARED" },
    });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/documents");
    const init = lastInit();
    expect(init.method).toBe("POST");
    const body = init.body as FormData;
    expect(body).toBeInstanceOf(FormData);
    expect(body.get("file")).toBe(file);
    const metadataPart = body.get("metadata") as Blob;
    expect(metadataPart.type).toBe("application/json");
    expect(JSON.parse(await metadataPart.text())).toEqual({
      name: "Hello",
      category: "OTHER",
      visibilityTier: "COMPANY_SHARED",
    });

    // No Content-Type set by the caller -- apiFetch/fetch itself must add the multipart boundary.
    const headers = new Headers(init.headers);
    expect(headers.has("Content-Type")).toBe(false);

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.index() });
  });
});

describe("useAddVersion", () => {
  it("appends a version with a file-only multipart body, and invalidates the document detail AND the case listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "v-2", documentId: "d-1", versionNo: 2 }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useAddVersion(), { wrapper: Wrapper });

    const file = new File(["v2"], "v2.pdf", { type: "application/pdf" });
    await result.current.mutateAsync({ documentId: "d-1", caseId: "c-1", file });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/versions");
    const init = lastInit();
    const body = init.body as FormData;
    expect(body.get("file")).toBe(file);
    // No metadata part at all -- addVersion's own multipart shape differs from upload's.
    expect(body.get("metadata")).toBeNull();

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-1") });
  });
});

describe("usePatchDocument", () => {
  it("PATCHes and invalidates the document detail and the case listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "d-1", caseId: "c-1", name: "Renamed" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => usePatchDocument(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "d-1", body: { name: "Renamed" } });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1");
    expect(lastInit().method).toBe("PATCH");
    expect(JSON.parse(lastInit().body as string)).toEqual({ name: "Renamed" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-1") });
  });
});

describe("useRetireDocument", () => {
  it("retires with a reason and invalidates the document detail and the case listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "d-1", caseId: "c-1", status: "RETIRED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useRetireDocument(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "d-1", reason: "Superseded" });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/retire");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "Superseded" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-1") });
  });
});

describe("useShareDocument", () => {
  it("shares with a principal and invalidates only the document detail", async () => {
    fetchMock.mockResolvedValue(reply({ id: "s-1", documentId: "d-1" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useShareDocument(), { wrapper: Wrapper });
    await result.current.mutateAsync({ documentId: "d-1", body: { principalType: "CONTACT", principalId: "ct-1" } });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/shares");
    expect(JSON.parse(lastInit().body as string)).toEqual({ principalType: "CONTACT", principalId: "ct-1" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledTimes(1);
  });
});

describe("useRevokeShare", () => {
  it("DELETEs a share and invalidates the document detail", async () => {
    fetchMock.mockResolvedValue(reply({ id: "s-1", documentId: "d-1" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useRevokeShare(), { wrapper: Wrapper });
    await result.current.mutateAsync({ documentId: "d-1", shareId: "s-1" });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/shares/s-1");
    expect(lastInit().method).toBe("DELETE");

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
  });
});

describe("useLinkDocument", () => {
  it("links a document into a second case, and invalidates the document detail AND only the newly-linked case's listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "l-1", documentId: "d-1", caseId: "c-2" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useLinkDocument(), { wrapper: Wrapper });
    await result.current.mutateAsync({ documentId: "d-1", caseId: "c-2" });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/links");
    expect(JSON.parse(lastInit().body as string)).toEqual({ caseId: "c-2" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-2") });
    // The document's home case (never passed here at all) gets no invalidation call --
    // forCase's own home-or-linked filter means it was never affected structurally.
    expect(invalidateSpy).toHaveBeenCalledTimes(2);
  });
});

describe("useDocumentRequests", () => {
  it("lists a case's document requests", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "r-1", caseId: "c-1" }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocumentRequests("c-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/document-requests");
  });

  it("does not fire without a case id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useDocumentRequests(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useCreateDocumentRequest", () => {
  it("creates an ad-hoc request under a case, and invalidates that case's request listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-9", caseId: "c-1" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useCreateDocumentRequest(), { wrapper: Wrapper });
    await result.current.mutateAsync({ caseId: "c-1", category: "TAX", requiresReview: false });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/document-requests");
    expect(JSON.parse(lastInit().body as string)).toEqual({ category: "TAX", requiresReview: false });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.requestsForCase("c-1") });
  });
});

describe("useWithdrawRequest", () => {
  it("withdraws with a reason, and invalidates that case's request listing", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-1", caseId: "c-1", status: "WITHDRAWN" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useWithdrawRequest(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "r-1", reason: "No longer needed" });

    expect(lastUrl()).toBe("/api/t/acme/document-requests/r-1/withdraw");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "No longer needed" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.requestsForCase("c-1") });
  });
});

describe("useFulfilRequest", () => {
  /**
   * The behaviour the brief calls out explicitly: fulfilling a
   * requirement-linked request can satisfy a requirement and complete a
   * milestone, the identical shape `useChangeTaskStatus` already handles for
   * tasks -- so this invalidates the request listing, the document listing,
   * AND the roadmap, not just the request's own state.
   */
  it("fulfils with a document id, and invalidates the request listing, the document listing, and the roadmap", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-1", caseId: "c-1", status: "FULFILLED", fulfilledDocumentId: "d-1" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useFulfilRequest(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "r-1", documentId: "d-1" });

    expect(lastUrl()).toBe("/api/t/acme/document-requests/r-1/fulfil");
    expect(JSON.parse(lastInit().body as string)).toEqual({ documentId: "d-1" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.requestsForCase("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.forCase("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: caseKeys.roadmap("c-1") });
  });
});

describe("useReviewVersion", () => {
  /** The brief's own explicit callout: reviewing must invalidate the document AND the pending queue. */
  it("reviews a version, and invalidates the document detail AND the pending-review queue", async () => {
    fetchMock.mockResolvedValue(reply({ id: "v-1", documentId: "d-1", versionNo: 1, reviewStatus: "APPROVED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useReviewVersion(), { wrapper: Wrapper });
    await result.current.mutateAsync({ documentId: "d-1", versionNo: 1, decision: "APPROVED" });

    expect(lastUrl()).toBe("/api/t/acme/documents/d-1/versions/1/review");
    expect(JSON.parse(lastInit().body as string)).toEqual({ decision: "APPROVED" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.detail("d-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: documentKeys.pending() });
  });
});
