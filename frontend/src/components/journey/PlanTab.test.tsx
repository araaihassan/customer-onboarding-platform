import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@testing-library/jest-dom/vitest";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { PlanTab, type PlanPreviewMilestone } from "./PlanTab";

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

const visible: PlanPreviewMilestone = { id: "m-1", name: "Kickoff", portalVisible: true };
const internalOnly: PlanPreviewMilestone = { id: "m-2", name: "Internal staging", portalVisible: false };

function mockRevisions(revisions: unknown[] = []) {
  fetchMock.mockImplementation((url: string) => {
    if (url.includes("/plan-revisions")) return Promise.resolve(reply(revisions));
    return Promise.resolve(reply({}));
  });
}

beforeEach(() => {
  permissions = { "plan.issue": ["ALL"], "plan.approve_schedule": ["ALL"] };
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderTab(milestones: PlanPreviewMilestone[] = [visible, internalOnly]) {
  return render(<PlanTab caseId="c1" milestones={milestones} />, { wrapper: makeWrapper() });
}

describe("PlanTab", () => {
  it("previews exactly what will be snapshotted before issuing", async () => {
    mockRevisions([]);
    renderTab();

    await waitFor(() => expect(screen.getByText("No schedule revisions yet")).toBeInTheDocument());

    fireEvent.click(screen.getByRole("button", { name: "Issue a schedule revision" }));

    expect(screen.getByText("Kickoff")).toBeInTheDocument();
    expect(screen.queryByText("Internal staging")).not.toBeInTheDocument();
  });

  it("hides the issue action without plan.issue", async () => {
    permissions = {};
    mockRevisions([]);
    renderTab();

    await waitFor(() => expect(screen.getByText("No schedule revisions yet")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Issue a schedule revision" })).toBeNull();
  });

  it("submits the issue with the trimmed note, then closes the dialog", async () => {
    mockRevisions([]);
    renderTab();

    await waitFor(() => expect(screen.getByText("No schedule revisions yet")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Issue a schedule revision" }));
    fireEvent.click(screen.getByRole("button", { name: "Issue" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/cases/c1/plan-revisions"),
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });

  it("renders the revision list once revisions exist", async () => {
    mockRevisions([
      { id: "r-2", revisionNumber: 2, status: "ISSUED", issuedAt: "2026-09-01T00:00:00Z" },
      { id: "r-1", revisionNumber: 1, status: "APPROVED", issuedAt: "2026-08-01T00:00:00Z" },
    ]);
    renderTab();

    await waitFor(() => expect(screen.getByText("Revision 2")).toBeInTheDocument());
    expect(screen.getByText("Revision 1")).toBeInTheDocument();
  });
});
