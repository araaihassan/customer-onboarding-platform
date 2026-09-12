import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@testing-library/jest-dom/vitest";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { PlanRevision } from "@/lib/api/plans";
import { PlanRevisionList } from "./PlanRevisionList";

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

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "r-1", status: "APPROVED" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

const issued: PlanRevision = {
  id: "r-1",
  revisionNumber: 1,
  status: "ISSUED",
  issuedAt: "2026-09-01T00:00:00Z",
  issueNote: "First cut",
};

describe("PlanRevisionList", () => {
  it("renders an empty state when there are no revisions yet", () => {
    render(<PlanRevisionList caseId="c1" revisions={[]} canDecide={false} />, { wrapper: makeWrapper() });
    expect(screen.getByText("No schedule revisions yet")).toBeInTheDocument();
  });

  it("shows the revision number and date in mono, and the issue note", () => {
    render(<PlanRevisionList caseId="c1" revisions={[issued]} canDecide={false} />, { wrapper: makeWrapper() });
    expect(screen.getByText("Revision 1")).toBeInTheDocument();
    expect(screen.getByText("2026-09-01")).toBeInTheDocument();
    expect(screen.getByText("First cut")).toBeInTheDocument();
  });

  it("only offers decide controls for the ISSUED revision to a plan.approve_schedule holder", () => {
    render(<PlanRevisionList caseId="c1" revisions={[issued]} canDecide={true} />, { wrapper: makeWrapper() });
    expect(screen.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Reject" })).toBeInTheDocument();
  });

  it("hides decide controls without canDecide", () => {
    render(<PlanRevisionList caseId="c1" revisions={[issued]} canDecide={false} />, { wrapper: makeWrapper() });
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("calls the decision endpoint with the chosen outcome", async () => {
    render(<PlanRevisionList caseId="c1" revisions={[issued]} canDecide={true} />, { wrapper: makeWrapper() });
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/cases/c1/plan-revisions/r-1/decision"),
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });
});
