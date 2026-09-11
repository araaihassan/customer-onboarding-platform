import type { ReactNode } from "react";
import { beforeEach, describe, expect, it } from "vitest";
import { vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError, __setAccessToken, setTenantSlug } from "@/lib/api/client";
import {
  parseProblems,
  useCloneTemplate,
  useCreateDraft,
  useCreateTemplate,
  useDefinition,
  useMigrate,
  useMigrationPreview,
  usePublish,
  useRefreshFromSource,
  useSaveDraft,
  useWorkflows,
} from "./workflows";

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
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

describe("useWorkflows", () => {
  it("lists every template the caller may view", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "t-1", name: "Standard" }]));

    const { result } = renderHook(() => useWorkflows(), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/workflows");
    expect(result.current.data).toEqual([{ id: "t-1", name: "Standard" }]);
  });
});

describe("useDefinition", () => {
  it("reads one version's whole graph", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-1", stages: [] }));

    const { result } = renderHook(() => useDefinition("t-1", "v-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-1/versions/v-1");
  });

  it("does not fire until both ids are present", () => {
    const { result } = renderHook(() => useDefinition("", ""), { wrapper: makeWrapper() });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("mutations", () => {
  it("creates a template with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-9" }, 201));

    const { result } = renderHook(() => useCreateTemplate(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ name: "Standard Onboarding" });

    expect(lastUrl()).toBe("/api/t/acme/workflows");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ name: "Standard Onboarding" });
  });

  it("creates a draft under its template with POST", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-2" }, 201));

    const { result } = renderHook(() => useCreateDraft(), { wrapper: makeWrapper() });
    await result.current.mutateAsync("t-1");

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-1/versions");
    expect(lastInit().method).toBe("POST");
  });

  it("saves the whole draft with one PUT", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-1" }));

    const { result } = renderHook(() => useSaveDraft(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({
      templateId: "t-1",
      versionId: "v-1",
      body: { stages: [], attributes: [], lockVersion: 0 },
    });

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-1/versions/v-1");
    expect(lastInit().method).toBe("PUT");
    expect(JSON.parse(lastInit().body as string)).toEqual({ stages: [], attributes: [], lockVersion: 0 });
  });

  it("publishes with POST", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-1", status: "PUBLISHED" }));

    const { result } = renderHook(() => usePublish(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ templateId: "t-1", versionId: "v-1" });

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-1/versions/v-1/publish");
    expect(lastInit().method).toBe("POST");
  });

  /** The 422 must reach the caller intact -- the publish panel renders every problem. */
  it("surfaces a 422 as an ApiError carrying the problem list", async () => {
    fetchMock.mockResolvedValue(reply({ problems: ["Stage 'Legal' has no milestones"] }, 422));

    const { result } = renderHook(() => usePublish(), { wrapper: makeWrapper() });
    await expect(result.current.mutateAsync({ templateId: "t-1", versionId: "v-1" }))
      .rejects.toBeInstanceOf(ApiError);
  });
});

describe("useCloneTemplate", () => {
  it("clones a catalogue template for a customer with POST", async () => {
    fetchMock.mockResolvedValue(
      reply({ id: "t-9", name: "Acme Onboarding", customerId: "cust-1", clonedFromTemplateId: "t-1" }, 201),
    );

    const { result } = renderHook(() => useCloneTemplate(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({
      templateId: "t-1",
      body: { customerId: "cust-1", name: "Acme Onboarding" },
    });

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-1/clone");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ customerId: "cust-1", name: "Acme Onboarding" });
  });

  /** This customer already holds a clone of this template -- V18's own partial unique index. */
  it("surfaces a 409 as an ApiError", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "Already cloned" }, 409));

    const { result } = renderHook(() => useCloneTemplate(), { wrapper: makeWrapper() });
    await expect(
      result.current.mutateAsync({ templateId: "t-1", body: { customerId: "cust-1", name: "Acme Onboarding" } }),
    ).rejects.toBeInstanceOf(ApiError);
  });
});

describe("useRefreshFromSource", () => {
  it("refreshes a customer template from its catalogue source with POST, no body", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-9", templateId: "t-9", status: "DRAFT" }, 201));

    const { result } = renderHook(() => useRefreshFromSource(), { wrapper: makeWrapper() });
    const definition = await result.current.mutateAsync("t-9");

    expect(lastUrl()).toBe("/api/t/acme/workflows/t-9/refresh");
    expect(lastInit().method).toBe("POST");
    expect(lastInit().body).toBeUndefined();
    // The caller routes straight into the editor with this -- unlike clone, refresh's
    // response carries the new draft's own versionId.
    expect(definition).toEqual({ versionId: "v-9", templateId: "t-9", status: "DRAFT" });
  });

  /** The customer template already has an open draft -- the same conflict createDraft guards against. */
  it("surfaces a 409 as an ApiError", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "Already has an open draft", versionId: "open-1" }, 409));

    const { result } = renderHook(() => useRefreshFromSource(), { wrapper: makeWrapper() });
    await expect(result.current.mutateAsync("t-9")).rejects.toBeInstanceOf(ApiError);
  });
});

describe("useMigrationPreview", () => {
  it("previews a target version by query param", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-5", onVersion: 31, eligible: 18, candidates: [] }));

    const { result } = renderHook(() => useMigrationPreview("v-5"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/migration?versionId=v-5");
    expect(result.current.data).toEqual({ versionId: "v-5", onVersion: 31, eligible: 18, candidates: [] });
  });

  it("does not fire without a version id", () => {
    const { result } = renderHook(() => useMigrationPreview(undefined), { wrapper: makeWrapper() });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useMigrate", () => {
  it("migrates the chosen cases onto a target version with POST", async () => {
    fetchMock.mockResolvedValue(reply({ migrated: 2 }));

    const { result } = renderHook(() => useMigrate(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ versionId: "v-5", caseIds: ["c-1", "c-2"] });

    expect(lastUrl()).toBe("/api/t/acme/cases/migration");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ versionId: "v-5", caseIds: ["c-1", "c-2"] });
  });

  /** migrate() refuses an ineligible case rather than silently skipping it -- the panel must surface that as an error. */
  it("surfaces a 409 as an ApiError when a requested case is not eligible", async () => {
    fetchMock.mockResolvedValue(reply("Case is not eligible: stage no longer exists", 409));

    const { result } = renderHook(() => useMigrate(), { wrapper: makeWrapper() });
    await expect(result.current.mutateAsync({ versionId: "v-5", caseIds: ["c-1"] }))
      .rejects.toBeInstanceOf(ApiError);
  });
});

describe("parseProblems", () => {
  it("extracts the problem list from a ProblemList body", () => {
    const message = JSON.stringify({ problems: ["a", "b"] });
    expect(parseProblems(message)).toEqual(["a", "b"]);
  });

  it("falls back to the raw message when the body is not a problem list", () => {
    expect(parseProblems("Not found")).toEqual(["Not found"]);
  });
});
