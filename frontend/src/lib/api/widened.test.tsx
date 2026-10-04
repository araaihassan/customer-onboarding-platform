import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys, toUpdateCaseRequest, useUpdateCase, type Case, type UpdateCaseRequest } from "./cases";
import { adminKeys, useUpdateDepartment } from "./admin";
import { documentKeys, useRemindRequest } from "./documents";
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

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("useUpdateCase", () => {
  it("PUTs exactly the body given, sets the detail and invalidates the clock and exceptions", async () => {
    const updated = { id: "c1", name: "N", ownerUserId: "u2" };
    fetchMock.mockResolvedValue(reply(updated));
    const { client, spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateCase(), { wrapper: Wrapper });
    const body: UpdateCaseRequest = { name: "N", ownerUserId: "u2", attributes: {} };
    await act(async () => {
      await result.current.mutateAsync({ caseId: "c1", body });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/cases/c1");
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(JSON.stringify(body));
    expect(client.getQueryData(caseKeys.detail("c1"))).toEqual(updated);
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.clock("c1")));
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.exceptions()));
  });
});

describe("toUpdateCaseRequest", () => {
  const c: Case = {
    id: "c1",
    name: "Acme",
    ownerUserId: "u1",
    owningDepartmentId: "d1",
    owningTeamId: "t1",
    attributes: { tier: "gold" },
  };

  it("copies every field from the case and applies the patch", () => {
    expect(toUpdateCaseRequest(c, { ownerUserId: "u2" })).toEqual({
      name: "Acme",
      ownerUserId: "u2",
      owningDepartmentId: "d1",
      owningTeamId: "t1",
      attributes: { tier: "gold" },
    });
  });

  it("carries every key of the generated UpdateCaseRequest (full-replace guard)", () => {
    // Record<keyof UpdateCaseRequest, true> fails tsc when the generated type gains a field.
    const required: Record<keyof UpdateCaseRequest, true> = {
      name: true,
      ownerUserId: true,
      owningDepartmentId: true,
      owningTeamId: true,
      attributes: true,
    };
    const out = toUpdateCaseRequest(c, {});
    for (const key of Object.keys(required)) expect(out).toHaveProperty(key);
  });
});

describe("useUpdateDepartment", () => {
  it("PUTs the department and invalidates the departments list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "d1" }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateDepartment(), { wrapper: Wrapper });
    const body = { name: "Ops", description: "x", headUserId: "u1" };
    await act(async () => {
      await result.current.mutateAsync({ id: "d1", body });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/departments/d1");
    const init = fetchMock.mock.calls[0]![1] as RequestInit;
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(JSON.stringify(body));
    expect(invalidated(spy)).toContain(JSON.stringify(adminKeys.departments()));
  });
});

describe("useRemindRequest", () => {
  it("POSTs the reminder and invalidates the case's requests and the exceptions", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r1" }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useRemindRequest(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({ requestId: "r1", caseId: "c1" });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/document-requests/r1/remind");
    expect((fetchMock.mock.calls[0]![1] as RequestInit).method).toBe("POST");
    expect(invalidated(spy)).toContain(JSON.stringify(documentKeys.requestsForCase("c1")));
    expect(invalidated(spy)).toContain(JSON.stringify(slaKeys.exceptions()));
  });
});
