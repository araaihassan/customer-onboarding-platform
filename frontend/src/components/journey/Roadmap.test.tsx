import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
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

function milestone(name: string): MilestoneRoadmap {
  return { id: name, name, status: "PENDING", requirements: [] };
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
  it("suppresses the stage header for a single same-named milestone", () => {
    const nineOneToOneStages: StageRoadmap[] = [
      "Registration", "Sales Approval", "Agreement", "Document Collection", "Verification",
      "Technical Setup", "Testing", "Training", "Go Live",
    ].map((name, i) => ({ id: `s-${i}`, name, ordinal: i, milestones: [milestone(name)] }));

    renderRoadmap(nineOneToOneStages);

    expect(screen.queryByRole("heading", { name: "Registration" })).toBeNull();
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(9);
  });

  it("shows the stage header when a stage fans out", () => {
    const registrationWithTwoMilestones: StageRoadmap[] = [
      { id: "s-0", name: "Registration", ordinal: 0, milestones: [milestone("Sign up"), milestone("Verify email")] },
    ];

    renderRoadmap(registrationWithTwoMilestones);

    expect(screen.getByRole("heading", { name: "Registration" })).not.toBeNull();
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(2);
  });

  it("shows the header when one milestone has a different name from its stage", () => {
    const stages: StageRoadmap[] = [
      { id: "s-0", name: "Registration", ordinal: 0, milestones: [milestone("Sign up")] },
    ];

    renderRoadmap(stages);

    expect(screen.getByRole("heading", { name: "Registration" })).not.toBeNull();
    expect(screen.getAllByTestId("milestone-row")).toHaveLength(1);
  });

  it("renders nothing for a stage with no milestones yet", () => {
    const stages: StageRoadmap[] = [{ id: "s-0", name: "Go Live", ordinal: 0, milestones: [] }];
    renderRoadmap(stages);
    expect(screen.getByRole("heading", { name: "Go Live" })).not.toBeNull();
    expect(screen.queryAllByTestId("milestone-row")).toHaveLength(0);
  });
});
