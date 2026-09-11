import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys } from "./cases";
import {
  programmeKeys,
  useAddJourney,
  useAddProgrammeParticipant,
  useCreateProgramme,
  useDeactivateProgramme,
  useProgramme,
  useProgrammes,
  useRemoveJourney,
  useRemoveProgrammeParticipant,
  useUpdateProgramme,
} from "./programmes";
import {
  planRevisionKeys,
  shapeApprovalKeys,
  useDecideRevision,
  useDecideShape,
  useIssueRevision,
  usePlanRevisions,
  useRevisionDiff,
  useShapeApproval,
  useSubmitShape,
} from "./plans";

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

describe("useProgrammes", () => {
  it("lists a customer's programmes, newest first", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "p-1", customerId: "cust-1" }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useProgrammes("cust-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers/cust-1/programmes");
  });

  it("does not fire without a customer id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useProgrammes(), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useProgramme", () => {
  it("reads the programme container -- its own fields, visible journeys, and the rollup", async () => {
    fetchMock.mockResolvedValue(
      reply({ programme: { id: "p-1", name: "Acme Rollout" }, journeys: [], rolledUpProgressPercent: 40, journeysCovered: 2 }),
    );

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useProgramme("p-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/programmes/p-1");
    expect(result.current.data?.rolledUpProgressPercent).toBe(40);
  });

  it("does not fire without a programme id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useProgramme(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useCreateProgramme", () => {
  it("creates a programme with POST, and invalidates that customer's programme list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "p-9", customerId: "cust-1", name: "Acme Rollout" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useCreateProgramme(), { wrapper: Wrapper });
    await result.current.mutateAsync({ name: "Acme Rollout", customerId: "cust-1" });

    expect(lastUrl()).toBe("/api/t/acme/programmes");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ name: "Acme Rollout", customerId: "cust-1" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.forCustomer("cust-1") });
  });
});

describe("useUpdateProgramme", () => {
  it("replaces a programme with PUT, and invalidates its detail and its customer's list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "p-1", customerId: "cust-1", name: "Renamed" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useUpdateProgramme(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "p-1", body: { name: "Renamed" } });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p-1");
    expect(lastInit().method).toBe("PUT");
    expect(JSON.parse(lastInit().body as string)).toEqual({ name: "Renamed" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.forCustomer("cust-1") });
  });
});

describe("useDeactivateProgramme", () => {
  it("deactivates with POST, and invalidates its detail and (when given) its customer's list", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useDeactivateProgramme(), { wrapper: Wrapper });
    await result.current.mutateAsync({ id: "p-1", customerId: "cust-1" });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p-1/deactivate");
    expect(lastInit().method).toBe("POST");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.forCustomer("cust-1") });
  });
});

describe("useAddJourney", () => {
  /** Verbatim from the task brief: the one required behaviour for this hook. */
  it("invalidates the programme detail and the case list after adding a journey", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { client, Wrapper } = makeWrapper();
    const invalidate = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useAddJourney(), { wrapper: Wrapper });
    await act(() => result.current.mutateAsync({ programmeId: "p1", caseId: "c1" }));

    expect(invalidate).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p1") });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: caseKeys.all });
  });

  it("posts the case id under the programme's journeys collection", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useAddJourney(), { wrapper: Wrapper });
    await result.current.mutateAsync({ programmeId: "p1", caseId: "c1" });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p1/journeys");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ caseId: "c1" });
  });
});

describe("useRemoveJourney", () => {
  it("unlinks a case with POST .../remove, and invalidates the programme detail and the case list", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useRemoveJourney(), { wrapper: Wrapper });
    await result.current.mutateAsync({ programmeId: "p1", caseId: "c1" });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p1/journeys/c1/remove");
    expect(lastInit().method).toBe("POST");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: caseKeys.all });
  });
});

describe("useAddProgrammeParticipant", () => {
  it("adds a participant with POST, and invalidates the programme detail", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useAddProgrammeParticipant(), { wrapper: Wrapper });
    await result.current.mutateAsync({ programmeId: "p1", userId: "u1", relationshipType: "PARTICIPANT" });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p1/participants");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ userId: "u1", relationshipType: "PARTICIPANT" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p1") });
  });
});

describe("useRemoveProgrammeParticipant", () => {
  it("removes a participant with POST .../remove, and invalidates the programme detail", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useRemoveProgrammeParticipant(), { wrapper: Wrapper });
    await result.current.mutateAsync({ programmeId: "p1", userId: "u1" });

    expect(lastUrl()).toBe("/api/t/acme/programmes/p1/participants/u1/remove");
    expect(lastInit().method).toBe("POST");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: programmeKeys.detail("p1") });
  });
});

describe("useShapeApproval", () => {
  it("reads the latest shape approval row for one template version", async () => {
    fetchMock.mockResolvedValue(reply({ id: "sa-1", versionId: "v1", status: "SUBMITTED" }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useShapeApproval("t1", "v1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/workflows/t1/versions/v1/shape-approval");
  });

  it("does not fire without both a template and a version id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useShapeApproval("t1", ""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useSubmitShape", () => {
  it("submits a version for gate 1 with POST and no body, and invalidates the shape approval read", async () => {
    fetchMock.mockResolvedValue(reply({ id: "sa-1", versionId: "v1", status: "SUBMITTED" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useSubmitShape(), { wrapper: Wrapper });
    await result.current.mutateAsync({ templateId: "t1", versionId: "v1" });

    expect(lastUrl()).toBe("/api/t/acme/workflows/t1/versions/v1/shape-approval");
    expect(lastInit().method).toBe("POST");
    expect(lastInit().body).toBeUndefined();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: shapeApprovalKeys.detail("t1", "v1") });
  });
});

describe("useDecideShape", () => {
  it("decides a submitted gate-1 approval with POST, and invalidates the shape approval read", async () => {
    fetchMock.mockResolvedValue(reply({ id: "sa-1", versionId: "v1", status: "APPROVED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useDecideShape(), { wrapper: Wrapper });
    await result.current.mutateAsync({ templateId: "t1", versionId: "v1", outcome: "APPROVED", note: "Looks good" });

    expect(lastUrl()).toBe("/api/t/acme/workflows/t1/versions/v1/shape-approval/decision");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ outcome: "APPROVED", note: "Looks good" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: shapeApprovalKeys.detail("t1", "v1") });
  });
});

describe("usePlanRevisions", () => {
  it("lists every schedule revision issued for a case, newest first", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "r-1", caseId: "c-1", revisionNumber: 1 }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => usePlanRevisions("c-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/plan-revisions");
  });

  it("does not fire without a case id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => usePlanRevisions(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useIssueRevision", () => {
  it("issues a revision with POST, and invalidates that case's revision list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-2", caseId: "c-1", revisionNumber: 2 }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useIssueRevision(), { wrapper: Wrapper });
    await result.current.mutateAsync({ caseId: "c-1", note: "Schedule shift" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/plan-revisions");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ note: "Schedule shift" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: planRevisionKeys.forCase("c-1") });
  });
});

describe("useDecideRevision", () => {
  /**
   * The behaviour PlanRevisionController's own javadoc calls out: approving a
   * case's first-ever revision releases its hold (Q22/Q23), so this must
   * invalidate the case's own detail and roadmap too, not just the revision
   * list -- otherwise the roadmap keeps showing the case on hold.
   */
  it("decides a revision with POST, and invalidates the revision list, the case detail, and the roadmap", async () => {
    fetchMock.mockResolvedValue(reply({ id: "r-1", caseId: "c-1", status: "APPROVED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useDecideRevision(), { wrapper: Wrapper });
    await result.current.mutateAsync({ caseId: "c-1", revisionId: "r-1", outcome: "APPROVED" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/plan-revisions/r-1/decision");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ outcome: "APPROVED" });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: planRevisionKeys.forCase("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: caseKeys.detail("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: caseKeys.roadmap("c-1") });
  });
});

describe("useRevisionDiff", () => {
  it("reads the per-milestone diff between two revisions", async () => {
    fetchMock.mockResolvedValue(reply({ rows: [{ milestoneName: "Kickoff", changeKind: "DATE_CHANGED" }] }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useRevisionDiff("c-1", "r-2", "r-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/plan-revisions/r-2/diff?against=r-1");
  });

  it("does not fire until case, revision, and comparison ids are all present", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useRevisionDiff("c-1", "r-2", ""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
