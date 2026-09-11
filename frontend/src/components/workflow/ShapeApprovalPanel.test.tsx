import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import "@testing-library/jest-dom/vitest";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { PlanShapeApproval, PlanShapeStage } from "@/lib/api/plans";
import { ShapeApprovalPanel } from "./ShapeApprovalPanel";

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

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderPanel(approval?: PlanShapeApproval, stages: PlanShapeStage[] = []) {
  return render(
    <ShapeApprovalPanel templateId="t-1" versionId="v-1" approval={approval} stages={stages} />,
    { wrapper: makeWrapper() },
  );
}

describe("ShapeApprovalPanel", () => {
  it("shows a rejected shape approval as blocking, with the reason", () => {
    permissions = {};
    renderPanel({ status: "REJECTED", decisionNote: "Dates too aggressive" });

    expect(screen.getByText("Rejected")).toBeInTheDocument();
    expect(screen.getByText("Dates too aggressive")).toBeInTheDocument();
    // Never colour alone: a rejected shape also says in words that it blocks progress.
    expect(screen.getByText(/cannot move forward/i)).toBeInTheDocument();
  });

  it("offers Submit for approval to workflow.manage when nothing has been submitted yet", () => {
    permissions = { "workflow.manage": ["ALL"] };
    renderPanel(undefined);

    expect(screen.getByRole("button", { name: "Submit for approval" })).toBeInTheDocument();
  });

  it("hides Submit for approval without workflow.manage", () => {
    permissions = {};
    renderPanel(undefined);

    expect(screen.queryByRole("button", { name: "Submit for approval" })).toBeNull();
  });

  it("offers decide controls to plan.approve_shape while SUBMITTED, and calls the decision endpoint", async () => {
    permissions = { "plan.approve_shape": ["ALL"] };
    fetchMock.mockResolvedValue(reply({ status: "APPROVED" }));
    renderPanel({ status: "SUBMITTED" });

    const approve = screen.getByRole("button", { name: "Approve" });
    fireEvent.click(approve);

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        expect.stringContaining("/workflows/t-1/versions/v-1/shape-approval/decision"),
        expect.objectContaining({ method: "POST" }),
      ),
    );
  });

  it("hides decide controls once a decision already exists", () => {
    permissions = { "plan.approve_shape": ["ALL"] };
    renderPanel({ status: "APPROVED" });

    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
  });

  it("renders the portal-visible artifact's stages and milestones", () => {
    permissions = {};
    renderPanel(
      { status: "APPROVED" },
      [{ id: "s-1", label: "Onboarding", milestones: [{ id: "m-1", label: "Kickoff", estimatedDurationDays: 3 }] }],
    );

    expect(screen.getByText("Onboarding")).toBeInTheDocument();
    expect(screen.getByText("Kickoff")).toBeInTheDocument();
  });
});
