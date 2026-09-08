import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys } from "./cases";
import { taskKeys, useCaseTasks, useChangeTaskStatus, useCreateTask, useMyWork, useWorkContexts, type Task } from "./tasks";
import { useAddComment, useComments } from "./comments";

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

describe("useCaseTasks", () => {
  it("lists a case's tasks", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "t-1", caseId: "c-1" }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useCaseTasks("c-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/tasks");
  });

  it("does not fire without a case id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useCaseTasks(""), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useMyWork", () => {
  it("reads the caller's own tasks, assignee=me, no bucket filter", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "t-1" }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useMyWork(), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/tasks?assignee=me");
  });

  it("narrows to one bucket when given", async () => {
    fetchMock.mockResolvedValue(reply([]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useMyWork({ bucket: "do_now" }), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/tasks?assignee=me&bucket=do_now");
  });
});

describe("useWorkContexts", () => {
  /**
   * Task 28's own addition: `WorkBoard` needs a case name and a customer
   * name per task (SCREENS.md §5), and `TaskView` carries only `caseId` --
   * so this is a two-hop lookup, case then customer. Two tasks sharing one
   * case must resolve to one case fetch, not two -- `caseIds` is de-duplicated
   * before it ever reaches `useQueries`.
   */
  it("resolves a case's name and its customer's display name, fetching each id once even when several tasks share it", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url === "/api/t/acme/cases/c-1") return reply({ id: "c-1", customerId: "cust-1", name: "Kickoff case" });
      if (url === "/api/t/acme/customers/cust-1") return reply({ id: "cust-1", displayName: "Northwind Foods" });
      return reply({}, 404);
    });

    const { Wrapper } = makeWrapper();
    const tasks = [
      { id: "t-1", caseId: "c-1" },
      { id: "t-2", caseId: "c-1" },
    ] as Task[];
    const { result } = renderHook(() => useWorkContexts(tasks), { wrapper: Wrapper });

    await waitFor(() =>
      expect(result.current.get("c-1")).toEqual({ caseName: "Kickoff case", customerName: "Northwind Foods" }),
    );

    expect(fetchMock.mock.calls.filter(([url]) => url === "/api/t/acme/cases/c-1")).toHaveLength(1);
    expect(fetchMock.mock.calls.filter(([url]) => url === "/api/t/acme/customers/cust-1")).toHaveLength(1);
  });

  it("returns an empty map and fires no request for an empty task list", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useWorkContexts([]), { wrapper: Wrapper });

    expect(result.current.size).toBe(0);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useCreateTask", () => {
  it("creates an ad-hoc task under a case with POST, and invalidates that case's task list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-9", caseId: "c-1" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useCreateTask(), { wrapper: Wrapper });
    await result.current.mutateAsync({
      caseId: "c-1",
      milestoneId: "m-1",
      title: "Collect W-9",
      priority: "MEDIUM",
    });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/tasks");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({
      milestoneId: "m-1",
      title: "Collect W-9",
      priority: "MEDIUM",
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: taskKeys.forCase("c-1") });
  });
});

describe("useChangeTaskStatus", () => {
  it("transitions a task's status with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-1", caseId: "c-1", status: "COMPLETED" }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useChangeTaskStatus(), { wrapper: Wrapper });
    await result.current.mutateAsync({ taskId: "t-1", status: "COMPLETED" });

    expect(lastUrl()).toBe("/api/t/acme/tasks/t-1/status");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({ status: "COMPLETED" });
  });

  /**
   * The one behaviour the brief calls out explicitly: completing a
   * requirement-linked task changes milestone state, so both the task list
   * and the roadmap must be invalidated -- not just the task's own detail.
   */
  it("invalidates both the case's task list and its roadmap, since a requirement-linked task can change milestone state", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-1", caseId: "c-1", status: "COMPLETED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useChangeTaskStatus(), { wrapper: Wrapper });
    await result.current.mutateAsync({ taskId: "t-1", status: "COMPLETED" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: taskKeys.forCase("c-1") });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: caseKeys.roadmap("c-1") });
  });

  /**
   * Task 28 fix round 1: the cross-case "My work" board (`useMyWork`, reading
   * `taskKeys.mine(filters)`) went stale after a status change made anywhere
   * else, since this mutation never invalidated that key family. Asserts the
   * whole family's prefix (`taskKeys.mineAll()`), not one specific filter
   * combination, since the board may be showing any bucket -- or none -- when
   * the change happens elsewhere.
   */
  it("invalidates the whole 'My work' key family, so the cross-case board reflects a status change made elsewhere", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-1", caseId: "c-1", status: "COMPLETED" }));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useChangeTaskStatus(), { wrapper: Wrapper });
    await result.current.mutateAsync({ taskId: "t-1", status: "COMPLETED" });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: taskKeys.mineAll() });
  });

  it("sends a cancellation reason when provided", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t-1", caseId: "c-1", status: "CANCELLED" }));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useChangeTaskStatus(), { wrapper: Wrapper });
    await result.current.mutateAsync({ taskId: "t-1", status: "CANCELLED", reason: "Requirement waived" });

    expect(JSON.parse(lastInit().body as string)).toEqual({ status: "CANCELLED", reason: "Requirement waived" });
  });
});

describe("useComments", () => {
  it("reads every comment on a resource, scoped by type and id", async () => {
    fetchMock.mockResolvedValue(reply([{ id: "cm-1", body: "Looks good" }]));

    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useComments("c-1", "TASK", "t-1"), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/comments?resourceType=TASK&resourceId=t-1");
  });

  it("does not fire without a case id", () => {
    const { Wrapper } = makeWrapper();
    const { result } = renderHook(() => useComments("", "TASK", "t-1"), { wrapper: Wrapper });
    expect(result.current.fetchStatus).toBe("idle");
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("useAddComment", () => {
  it("adds a comment to a resource with POST, and invalidates that resource's comment list", async () => {
    fetchMock.mockResolvedValue(reply({ id: "cm-9", caseId: "c-1", resourceType: "TASK", resourceId: "t-1" }, 201));

    const { client, Wrapper } = makeWrapper();
    const invalidateSpy = vi.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useAddComment(), { wrapper: Wrapper });
    await result.current.mutateAsync({ caseId: "c-1", resourceType: "TASK", resourceId: "t-1", body: "Looks good" });

    expect(lastUrl()).toBe("/api/t/acme/cases/c-1/comments");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toEqual({
      resourceType: "TASK",
      resourceId: "t-1",
      body: "Looks good",
    });
    expect(invalidateSpy).toHaveBeenCalled();
  });
});
