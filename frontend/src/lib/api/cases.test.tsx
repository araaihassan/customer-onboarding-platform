import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError, __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { useAdvance, useCase, useCases, useCreateCase, useHold, useResume, useRoadmap } from "./cases";

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

describe("useCases", () => {
  it("lists a customer's cases, newest first", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "c-1" }]));

    const { result } = renderHook(() => useCases("cust-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers/cust-1/cases");
  });

  it("does not fire without a customer id", () => {
    const { result } = renderHook(() => useCases(""), { wrapper: makeWrapper() });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("does not fire when disabled by the caller, e.g. someone without case.view", () => {
    const { result } = renderHook(() => useCases("cust-1", false), { wrapper: makeWrapper() });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useCase", () => {
  it("reads one case by id", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1", customerId: "cust-1" }));

    const { result } = renderHook(() => useCase("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1");
  });
});

describe("useRoadmap", () => {
  it("reads the case's whole stage graph in one call", async () => {
    fetchMock.mockResolvedValue(reply({ stages: [] }));

    const { result } = renderHook(() => useRoadmap("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/roadmap");
  });
});

describe("useCreateCase", () => {
  it("creates a case with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-9" }, 201));

    const { result } = renderHook(() => useCreateCase(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ customerId: "cust-1", templateId: "t-1", attributes: {} });

    expect(lastUrl()).toBe("/api/t/acme/cases");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({
      customerId: "cust-1",
      templateId: "t-1",
      attributes: {},
    });
  });

  /** The dialog renders every 422 problem against its own field. */
  it("surfaces a 422 as an ApiError carrying the problem list", async () => {
    fetchMock.mockResolvedValue(reply({ problems: ["industry is required"] }, 422));

    const { result } = renderHook(() => useCreateCase(), { wrapper: makeWrapper() });
    await expect(
      result.current.mutateAsync({ customerId: "cust-1", templateId: "t-1", attributes: {} }),
    ).rejects.toBeInstanceOf(ApiError);
  });
});

describe("useAdvance", () => {
  it("advances the case with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1", currentStageName: "Legal Review" }));

    const { result } = renderHook(() => useAdvance(), { wrapper: makeWrapper() });
    await result.current.mutateAsync("c-1");

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/advance");
    expect(lastInit().method).toBe("POST");
  });
});

describe("useHold", () => {
  it("holds the case with a reason", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1", status: "ON_HOLD" }));

    const { result } = renderHook(() => useHold(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ id: "c-1", reason: "Awaiting documents" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/hold");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "Awaiting documents" });
  });
});

describe("useResume", () => {
  it("resumes the case with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1", status: "ACTIVE" }));

    const { result } = renderHook(() => useResume(), { wrapper: makeWrapper() });
    await result.current.mutateAsync("c-1");

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/resume");
    expect(lastInit().method).toBe("POST");
  });
});
