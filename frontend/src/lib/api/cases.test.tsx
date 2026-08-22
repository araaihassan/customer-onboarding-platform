import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError, __setAccessToken, setTenantSlug } from "@/lib/api/client";
import {
  parseProblemDetail,
  useAdvance,
  useCase,
  useCases,
  useCreateCase,
  useDecideApproval,
  useForceComplete,
  useApprovals,
  useHold,
  useParticipants,
  useReopen,
  useResume,
  useRoadmap,
  useSatisfy,
  useWaive,
} from "./cases";

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

describe("useParticipants", () => {
  it("reads the case's participants, for resolving an owner id to a name", async () => {
    fetchMock.mockResolvedValue(reply([{ userId: "u-1", fullName: "Ada Lovelace", relationship: "OWNER" }]));

    const { result } = renderHook(() => useParticipants("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/participants");
  });
});

describe("useApprovals", () => {
  it("reads every approval recorded against the case, of both kinds", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "a-1", kind: "FORCE_COMPLETE", status: "PENDING" }]));

    const { result } = renderHook(() => useApprovals("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/approvals");
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

describe("useSatisfy", () => {
  it("satisfies a requirement with POST, ref/refType omitted for a plain manual check-off", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-1", status: "SATISFIED" }));

    const { result } = renderHook(() => useSatisfy(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ caseId: "c-1", requirementId: "r-1" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/requirements/r-1/satisfy");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({});
  });

  /** The case is on hold: refused rather than silently applied. */
  it("surfaces a 409 as an ApiError", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "Case is on hold" }, 409));

    const { result } = renderHook(() => useSatisfy(), { wrapper: makeWrapper() });
    await expect(result.current.mutateAsync({ caseId: "c-1", requirementId: "r-1" }))
      .rejects.toBeInstanceOf(ApiError);
  });
});

describe("useWaive", () => {
  it("waives a requirement with a reason", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-1", status: "WAIVED" }));

    const { result } = renderHook(() => useWaive(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ caseId: "c-1", requirementId: "r-1", reason: "Not applicable" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/requirements/r-1/waive");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "Not applicable" });
  });
});

describe("useForceComplete", () => {
  it("requests a forced completion with a reason", async () => {
    fetchMock.mockResolvedValue(reply({ id: "a-1", kind: "FORCE_COMPLETE", status: "PENDING" }, 201));

    const { result } = renderHook(() => useForceComplete(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ caseId: "c-1", milestoneId: "m-1", reason: "Customer waived in person" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/milestones/m-1/force-complete");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "Customer waived in person" });
  });
});

describe("useReopen", () => {
  it("reopens a milestone with a reason", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { result } = renderHook(() => useReopen(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ caseId: "c-1", milestoneId: "m-1", reason: "Customer corrected the document" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/milestones/m-1/reopen");
    expect(JSON.parse(lastInit().body as string)).toEqual({ reason: "Customer corrected the document" });
  });
});

describe("useDecideApproval", () => {
  it("decides a STAGE_EXIT approval against its own endpoint", async () => {
    fetchMock.mockResolvedValue(reply({ id: "a-1", kind: "STAGE_EXIT", status: "APPROVED" }));

    const { result } = renderHook(() => useDecideApproval(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({
      caseId: "c-1",
      approvalId: "a-1",
      kind: "STAGE_EXIT",
      approve: true,
      note: "Looks good",
    });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/stage-approvals/a-1/decide");
    expect(JSON.parse(lastInit().body as string)).toEqual({ approve: true, note: "Looks good" });
  });

  /**
   * The two kinds are two endpoints server-side (ApprovalController's own
   * javadoc: @RequirePermission is static, so one path could not carry both
   * approval.decide and milestone.force_approve). The hook must route to the
   * matching one, not always the first.
   */
  it("decides a FORCE_COMPLETE approval against the force-requests endpoint", async () => {
    fetchMock.mockResolvedValue(reply({ id: "a-2", kind: "FORCE_COMPLETE", status: "REJECTED" }));

    const { result } = renderHook(() => useDecideApproval(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ caseId: "c-1", approvalId: "a-2", kind: "FORCE_COMPLETE", approve: false });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/force-requests/a-2/decide");
  });

  /** Q5: the requester cannot decide their own force-complete request. */
  it("surfaces a 403 as an ApiError when the decider is the requester", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "The decider is the same actor who requested it" }, 403));

    const { result } = renderHook(() => useDecideApproval(), { wrapper: makeWrapper() });
    await expect(
      result.current.mutateAsync({ caseId: "c-1", approvalId: "a-2", kind: "FORCE_COMPLETE", approve: true }),
    ).rejects.toBeInstanceOf(ApiError);
  });
});

describe("parseProblemDetail", () => {
  it("extracts the detail from a ProblemDetail body", () => {
    const message = JSON.stringify({ type: "about:blank", title: "Forbidden", status: 403, detail: "Stage \"Legal Review\" write scope OWNER_ONLY does not admit the caller" });
    expect(parseProblemDetail(message)).toBe("Stage \"Legal Review\" write scope OWNER_ONLY does not admit the caller");
  });

  it("falls back to the raw message when the body is not a ProblemDetail", () => {
    expect(parseProblemDetail("Not found")).toBe("Not found");
  });
});
