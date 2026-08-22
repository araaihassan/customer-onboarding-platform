import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Approval } from "@/lib/api/cases";
import { ApprovalPanel } from "./ApprovalPanel";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

afterEach(cleanup);

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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const pendingForceComplete: Approval = {
  id: "a-1",
  kind: "FORCE_COMPLETE",
  milestoneId: "m-1",
  status: "PENDING",
  reason: "Customer confirmed by phone",
  requestedBy: "u-requester",
};

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "a-1", status: "APPROVED" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderPanel(approval: Approval) {
  return render(<ApprovalPanel caseId="c-1" approval={approval} />, { wrapper: makeWrapper() });
}

describe("ApprovalPanel", () => {
  it("shows the pending reason and decide controls for someone holding milestone.force_approve", () => {
    permissions = { "milestone.force_approve": ["ALL"] };
    renderPanel(pendingForceComplete);

    expect(screen.getByText("Customer confirmed by phone")).not.toBeNull();
    expect(screen.getByRole("button", { name: /approve/i })).not.toBeNull();
    expect(screen.getByRole("button", { name: /reject/i })).not.toBeNull();
  });

  it("hides decide controls without the permission", () => {
    permissions = {};
    renderPanel(pendingForceComplete);

    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /reject/i })).toBeNull();
  });

  it("hides decide controls once the approval is no longer pending", () => {
    permissions = { "milestone.force_approve": ["ALL"] };
    renderPanel({ ...pendingForceComplete, status: "APPROVED" });

    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();
  });

  it("gates a STAGE_EXIT approval on approval.decide instead", () => {
    permissions = { "milestone.force_approve": ["ALL"] };
    renderPanel({ ...pendingForceComplete, kind: "STAGE_EXIT" });
    expect(screen.queryByRole("button", { name: /approve/i })).toBeNull();

    permissions = { "approval.decide": ["ALL"] };
    renderPanel({ ...pendingForceComplete, kind: "STAGE_EXIT" });
    expect(screen.getByRole("button", { name: /approve/i })).not.toBeNull();
  });

  it("tells the requester they cannot approve their own request", async () => {
    permissions = { "milestone.force_approve": ["ALL"] };
    fetchMock.mockResolvedValueOnce(
      reply({ detail: "The requester cannot decide their own force-complete request" }, 403),
    );

    renderPanel(pendingForceComplete);
    fireEvent.click(screen.getByRole("button", { name: /approve/i }));

    await waitFor(() => expect(screen.getByText(/cannot decide their own/)).not.toBeNull());
  });
});
