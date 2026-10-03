import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { slaKeys, useCaseSlaClock, useSlaExceptions } from "./sla";

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, Wrapper };
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("sla hooks", () => {
  it("fetches the clock of a case", async () => {
    fetchMock.mockResolvedValue(reply({ caseId: "c1", state: "RUNNING" }));
    const { Wrapper } = setup();
    const { result } = renderHook(() => useCaseSlaClock("c1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/cases/c1/sla-clock");
    expect(result.current.data?.state).toBe("RUNNING");
  });

  it("treats a 404 as no clock, not an error", async () => {
    fetchMock.mockResolvedValue(reply({ message: "none" }, 404));
    const { Wrapper } = setup();
    const { result } = renderHook(() => useCaseSlaClock("c1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeNull();
    expect(result.current.isError).toBe(false);
  });

  it("surfaces non-404 errors", async () => {
    fetchMock.mockResolvedValue(reply({ message: "boom" }, 500));
    const { Wrapper } = setup();
    const { result } = renderHook(() => useCaseSlaClock("c1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isError).toBe(true));
  });

  it("does not fetch without a case id", () => {
    const { Wrapper } = setup();
    renderHook(() => useCaseSlaClock(""), { wrapper: Wrapper });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("fetches exceptions and polls every minute", async () => {
    fetchMock.mockResolvedValue(reply({ breached: [] }));
    const { client, Wrapper } = setup();
    const { result } = renderHook(() => useSlaExceptions(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/sla/exceptions");
    const query = client.getQueryCache().find({ queryKey: slaKeys.exceptions() });
    expect((query!.observers[0]!.options as { refetchInterval?: number }).refetchInterval).toBe(60_000);
  });
});
