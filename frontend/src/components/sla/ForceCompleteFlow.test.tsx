import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { ForceCompleteFlow } from "./ForceCompleteFlow";

const fetchMock = vi.fn();
function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}
const roadmap = {
  stages: [
    {
      id: "s-2",
      name: "Verification",
      milestones: [
        { id: "m-1", name: "Collect KYC", status: "ACTIVE" },
        { id: "m-2", name: "Sanctions check", status: "PENDING" },
        { id: "m-3", name: "Finished", status: "DONE" },
        { id: "m-4", name: "Skipped one", status: "SKIPPED" },
      ],
    },
  ],
};
function serve(approvals: unknown[] = []) {
  fetchMock.mockImplementation(async (url: string) => (String(url).endsWith("/approvals") ? reply(approvals) : reply(roadmap)));
}
function renderFlow(escalatedMilestoneId?: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  render(<ForceCompleteFlow caseId="c-1" stageName="Verification" escalatedMilestoneId={escalatedMilestoneId} onClose={vi.fn()} />, {
    wrapper,
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  serve();
});
afterEach(cleanup);

describe("ForceCompleteFlow", () => {
  it("goes straight to the reason dialog for a valid, incomplete escalated milestone", async () => {
    renderFlow("m-1");
    expect(await screen.findByLabelText(/reason/i)).toBeInTheDocument();
    expect(screen.queryByText("Force-complete a milestone")).toBeNull();
  });

  it("falls back to the picker when the escalated milestone is already DONE", async () => {
    renderFlow("m-3");
    expect(await screen.findByRole("button", { name: "Collect KYC" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/reason/i)).toBeNull();
  });

  it("falls back to the picker when the escalated milestone is SKIPPED", async () => {
    renderFlow("m-4");
    expect(await screen.findByRole("button", { name: "Collect KYC" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/reason/i)).toBeNull();
  });

  it("falls back to the picker when the escalated milestone already has a pending request", async () => {
    serve([{ id: "a-1", kind: "FORCE_COMPLETE", milestoneId: "m-1", status: "PENDING" }]);
    renderFlow("m-1");
    expect(await screen.findByRole("button", { name: "Sanctions check" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Collect KYC" })).toBeNull();
    expect(screen.queryByLabelText(/reason/i)).toBeNull();
  });

  it("falls back to the picker when the escalated milestone is not in the roadmap", async () => {
    renderFlow("m-gone");
    expect(await screen.findByRole("button", { name: "Collect KYC" })).toBeInTheDocument();
    expect(screen.queryByLabelText(/reason/i)).toBeNull();
  });

  it("uses the picker when no milestone escalation exists", async () => {
    renderFlow(undefined);
    expect(await screen.findByRole("button", { name: "Collect KYC" })).toBeInTheDocument();
  });
});
