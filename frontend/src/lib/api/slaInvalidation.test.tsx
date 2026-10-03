import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { caseKeys, useUpdateCase, useDecideApproval, useForceComplete, useHold, useReopen, useResume, useSatisfy, useWaive } from "./cases";
import { useCreateDocumentRequest, useFulfilRequest, useReviewVersion, useWithdrawRequest } from "./documents";
import { slaKeys } from "./sla";
import { useChangeTaskStatus, useUpdateTask } from "./tasks";
import { useRecordSignature } from "./agreements";
import { useMigrate } from "./workflows";

/**
 * The case page does not poll and refetchOnWindowFocus is off, so the SLA clock refetches only
 * when invalidated. Every mutation that can change a case's stage or open/close a pause must do so.
 */
const fetchMock = vi.fn();

function reply(body: unknown) {
  return { ok: true, status: 200, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const spy = vi.spyOn(client, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const keys = () => spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey));
  return { wrapper, keys };
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

type Case = {
  name: string;
  response: unknown;
  run: () => { hook: () => { mutateAsync: (v: never) => Promise<unknown> }; vars: unknown };
  /** What must be invalidated; `clock` is slaKeys.clock("c-1") unless `broad`, then slaKeys.all. */
  broad?: boolean;
};

const cases: Case[] = [
  { name: "useHold", response: { id: "c-1" }, run: () => ({ hook: useHold, vars: { id: "c-1", reason: "r" } }) },
  { name: "useResume", response: { id: "c-1" }, run: () => ({ hook: useResume, vars: "c-1" }) },
  { name: "useSatisfy", response: {}, run: () => ({ hook: useSatisfy, vars: { caseId: "c-1", requirementId: "r" } }) },
  { name: "useWaive", response: {}, run: () => ({ hook: useWaive, vars: { caseId: "c-1", requirementId: "r", reason: "x" } }) },
  { name: "useForceComplete", response: {}, run: () => ({ hook: useForceComplete, vars: { caseId: "c-1", milestoneId: "m", reason: "x" } }) },
  { name: "useReopen", response: {}, run: () => ({ hook: useReopen, vars: { caseId: "c-1", milestoneId: "m", reason: "x" } }) },
  {
    name: "useDecideApproval",
    response: {},
    run: () => ({ hook: useDecideApproval, vars: { caseId: "c-1", approvalId: "a", kind: "STAGE_EXIT", approve: true } }),
  },
  { name: "useFulfilRequest", response: { caseId: "c-1" }, run: () => ({ hook: useFulfilRequest, vars: { id: "d", documentId: "x" } }) },
  {
    name: "useReviewVersion (no case id on the version: broad)",
    response: {},
    broad: true,
    run: () => ({ hook: useReviewVersion, vars: { documentId: "d", versionNo: 1, decision: "APPROVE" } }),
  },
  { name: "useUpdateCase", response: { id: "c-1" }, run: () => ({ hook: useUpdateCase, vars: { caseId: "c-1", body: { name: "n" } } }) },
  { name: "useUpdateTask", response: { caseId: "c-1" }, run: () => ({ hook: useUpdateTask, vars: { taskId: "t", body: { title: "t", priority: "LOW", milestoneId: "m" } } }) },
  { name: "useChangeTaskStatus", response: { caseId: "c-1" }, run: () => ({ hook: useChangeTaskStatus, vars: { taskId: "t", status: "DONE" } }) },
  {
    name: "useCreateDocumentRequest",
    response: { caseId: "c-1" },
    run: () => ({ hook: useCreateDocumentRequest, vars: { caseId: "c-1", label: "x" } }),
  },
  { name: "useWithdrawRequest", response: { caseId: "c-1" }, run: () => ({ hook: useWithdrawRequest, vars: { id: "d", reason: "x" } }) },
  {
    name: "useRecordSignature",
    response: { agreement: { id: "ag", caseId: "c-1" } },
    run: () => ({ hook: useRecordSignature, vars: { id: "ag", signature: {} } }),
  },
  { name: "useMigrate", response: { migrated: 1 }, run: () => ({ hook: useMigrate, vars: { versionId: "v", caseIds: ["c-1"] } }) },
];

describe("mutations that can change a stage or a pause refresh the SLA clock and war-room feed", () => {
  it.each(cases)("$name", async ({ response, run, broad }) => {
    fetchMock.mockResolvedValue(reply(response));
    const { wrapper, keys } = setup();
    const { hook, vars } = run();
    const { result } = renderHook(() => hook(), { wrapper });
    await act(async () => {
      await result.current.mutateAsync(vars as never);
    });
    const k = keys();
    if (broad) {
      expect(k).toContain(JSON.stringify(slaKeys.all));
      expect(k).toContain(JSON.stringify([...caseKeys.all, "roadmap"]));
    } else {
      expect(k).toContain(JSON.stringify(slaKeys.clock("c-1")));
      expect(k).toContain(JSON.stringify(slaKeys.exceptions()));
    }
  });
});
