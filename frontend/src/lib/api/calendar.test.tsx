import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import {
  calendarKeys,
  useAddHoliday,
  useBusinessCalendar,
  useRemoveHoliday,
  useSlaPolicy,
  useUpdateBusinessCalendar,
  useUpdateSlaPolicy,
} from "./calendar";
import { slaKeys } from "./sla";

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
  const spy = vi.spyOn(client, "invalidateQueries");
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, spy, Wrapper };
}

function invalidated(spy: { mock: { calls: unknown[][] } }): string[] {
  return spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey));
}

function init(i: number): RequestInit {
  return fetchMock.mock.calls[i]![1] as RequestInit;
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("calendar hooks", () => {
  it("reads the calendar and the policy", async () => {
    fetchMock.mockResolvedValue(reply({ name: "Std" }));
    const { Wrapper } = setup();
    const a = renderHook(() => useBusinessCalendar(), { wrapper: Wrapper });
    const b = renderHook(() => useSlaPolicy(), { wrapper: Wrapper });
    await waitFor(() => expect(a.result.current.isSuccess && b.result.current.isSuccess).toBe(true));
    const urls = fetchMock.mock.calls.map((c) => c[0]);
    expect(urls).toContain("/api/t/acme/admin/business-calendar");
    expect(urls).toContain("/api/t/acme/admin/sla-policy");
  });

  it("PUTs the calendar and invalidates calendar and sla", async () => {
    fetchMock.mockResolvedValue(reply({ name: "Std" }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateBusinessCalendar(), { wrapper: Wrapper });
    const body = { name: "Std", timezone: "UTC", workingDays: [1, 2, 3] };
    await act(async () => {
      await result.current.mutateAsync(body);
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/business-calendar");
    expect(init(0).method).toBe("PUT");
    expect(init(0).body).toBe(JSON.stringify(body));
    expect(invalidated(spy)).toContain(JSON.stringify(calendarKeys.calendar()));
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.all));
  });

  it("POSTs a holiday and invalidates calendar and sla", async () => {
    fetchMock.mockResolvedValue(reply({ id: "h1" }, 201));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useAddHoliday(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({ date: "2026-12-25", name: "Xmas" });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/business-calendar/holidays");
    expect(init(0).method).toBe("POST");
    expect(invalidated(spy)).toContain(JSON.stringify(calendarKeys.calendar()));
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.all));
  });

  it("POSTs a holiday removal", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useRemoveHoliday(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync("h1");
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/business-calendar/holidays/h1/remove");
    expect(init(0).method).toBe("POST");
    expect(invalidated(spy)).toContain(JSON.stringify(calendarKeys.calendar()));
  });

  it("PUTs the policy and invalidates policy and sla", async () => {
    fetchMock.mockResolvedValue(reply({ atRiskDays: 2, escalateAfterOverdueDays: 3 }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateSlaPolicy(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({ atRiskDays: 2, escalateAfterOverdueDays: 3 });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/sla-policy");
    expect(init(0).method).toBe("PUT");
    expect(invalidated(spy)).toContain(JSON.stringify(calendarKeys.policy()));
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.all));
  });
});
