import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { MilestoneRoadmap, StageRoadmap } from "@/lib/api/cases";
import { Roadmap } from "./Roadmap";

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions: {} }) }));

afterEach(cleanup);

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function milestone(name: string, status: MilestoneRoadmap["status"] = "PENDING"): MilestoneRoadmap {
  return { id: name, name, status, requirements: [] };
}

beforeEach(() => {
  global.fetch = vi.fn() as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderRoadmap(stages: StageRoadmap[]) {
  return render(<Roadmap stages={stages} />, { wrapper: makeWrapper() });
}

describe("Roadmap rendering", () => {
  it("renders one StageAccordion header per stage, expanded by default", () => {
    const stages: StageRoadmap[] = [
      { id: "s-0", name: "Registration", ordinal: 0, milestones: [milestone("Company profile captured")] },
      {
        id: "s-1",
        name: "Sales Approval",
        ordinal: 1,
        milestones: [milestone("Handoff approved"), milestone("Second review")],
      },
    ];

    renderRoadmap(stages);

    expect(screen.getByRole("button", { name: /registration/i })).not.toBeNull();
    expect(screen.getByRole("button", { name: /sales approval/i })).not.toBeNull();
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(3);
  });

  it("collapses a stage's milestone rows when its header is clicked, and re-expands on a second click", () => {
    const stages: StageRoadmap[] = [
      { id: "s-0", name: "Registration", ordinal: 0, milestones: [milestone("Sign up"), milestone("Verify email")] },
    ];

    renderRoadmap(stages);
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(2);

    const header = screen.getByRole("button", { name: /registration/i });
    fireEvent.click(header);
    expect(screen.queryAllByTestId("milestone-row")).toHaveLength(0);

    fireEvent.click(header);
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(2);
  });

  it("only collapses the clicked stage, leaving other stages expanded", () => {
    const stages: StageRoadmap[] = [
      { id: "s-0", name: "Registration", ordinal: 0, milestones: [milestone("Sign up")] },
      { id: "s-1", name: "Agreement", ordinal: 1, milestones: [milestone("MSA drafted")] },
    ];

    renderRoadmap(stages);
    fireEvent.click(screen.getByRole("button", { name: /registration/i }));

    expect(screen.queryAllByTestId("milestone-row")).toHaveLength(1);
    expect(screen.getByText("MSA drafted")).not.toBeNull();
  });

  it("shows the stage title with no milestone rows for a stage with none yet", () => {
    const stages: StageRoadmap[] = [{ id: "s-0", name: "Go Live", ordinal: 0, milestones: [] }];
    renderRoadmap(stages);
    expect(screen.getByRole("button", { name: /go live/i })).not.toBeNull();
    expect(screen.queryAllByTestId("milestone-row")).toHaveLength(0);
  });

  it("marks a stage complete only once every milestone is DONE or SKIPPED", () => {
    const stages: StageRoadmap[] = [
      {
        id: "s-0",
        name: "Agreement",
        ordinal: 0,
        milestones: [milestone("MSA drafted", "DONE"), milestone("Signature collected", "SKIPPED")],
      },
    ];
    renderRoadmap(stages);
    expect(screen.getByText("Complete")).not.toBeNull();
  });

  it("marks a stage with no progress yet as upcoming", () => {
    const stages: StageRoadmap[] = [
      { id: "s-0", name: "Testing", ordinal: 0, milestones: [milestone("Customer acceptance test")] },
    ];
    renderRoadmap(stages);
    expect(screen.getByText("Upcoming")).not.toBeNull();
  });
});
