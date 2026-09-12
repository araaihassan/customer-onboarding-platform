import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Approval, MilestoneRoadmap, Participant } from "@/lib/api/cases";
import { MilestoneRow } from "./MilestoneRow";

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

const blocked: MilestoneRoadmap = {
  id: "m-1",
  name: "Technical Setup",
  status: "BLOCKED",
  dueDate: "2020-01-01",
  progressPercent: 20,
  blockedByMilestoneNames: ["Verification"],
  requirements: [],
};

const active: MilestoneRoadmap = {
  id: "m-2",
  name: "Testing",
  status: "ACTIVE",
  ownerUserId: "u-1",
  dueDate: "2099-01-01",
  progressPercent: 40,
  requirements: [],
};

const participants: Participant[] = [{ userId: "u-1", fullName: "Ada Lovelace", relationship: "OWNER" }];
const approvals: Approval[] = [];

beforeEach(() => {
  permissions = { "milestone.complete": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({}));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderRow(milestone: MilestoneRoadmap) {
  return render(
    <MilestoneRow caseId="c-1" milestone={milestone} participants={participants} approvals={approvals} />,
    { wrapper: makeWrapper() },
  );
}

describe("MilestoneRow", () => {
  it("pairs the blocked status colour with words, not colour alone", () => {
    renderRow(blocked);
    expect(screen.getByText("Blocked")).not.toBeNull();
    expect(screen.getByText(/blocked by verification/i)).not.toBeNull();
  });

  it("marks a past-due, still-open milestone with text, not colour alone", () => {
    renderRow(blocked);
    expect(screen.getByText(/overdue/i)).not.toBeNull();
  });

  it("does not mark a future due date as overdue", () => {
    renderRow(active);
    expect(screen.queryByText(/overdue/i)).toBeNull();
  });

  it("resolves the owner id to a name via the case's participants", () => {
    renderRow(active);
    expect(screen.getByText("Ada Lovelace")).not.toBeNull();
  });

  it("expands the panel and rotates the chevron on click, collapsed by default", () => {
    renderRow(active);
    const toggle = screen.getByRole("button", { name: /testing/i });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByText("Requirements")).toBeNull();

    fireEvent.click(toggle);

    expect(toggle.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByText("Requirements")).not.toBeNull();
  });

  it("offers force-complete to a holder of milestone.force_complete on an open milestone", () => {
    permissions = { "milestone.force_complete": ["ALL"] };
    renderRow(active);
    fireEvent.click(screen.getByRole("button", { name: /testing/i }));

    expect(screen.getByRole("button", { name: /force complete/i })).not.toBeNull();
  });

  it("hides force-complete without the permission", () => {
    permissions = {};
    renderRow(active);
    fireEvent.click(screen.getByRole("button", { name: /testing/i }));

    expect(screen.queryByRole("button", { name: /force complete/i })).toBeNull();
  });

  it("hides force-complete on a DONE milestone", () => {
    permissions = { "milestone.force_complete": ["ALL"] };
    renderRow({ ...active, status: "DONE" });
    fireEvent.click(screen.getByRole("button", { name: /testing/i }));

    expect(screen.queryByRole("button", { name: /force complete/i })).toBeNull();
  });

  it("shows the pending force-complete approval instead of the force-complete action", () => {
    permissions = { "milestone.force_complete": ["ALL"], "milestone.force_approve": ["ALL"] };
    render(
      <MilestoneRow
        caseId="c-1"
        milestone={active}
        participants={participants}
        approvals={[{ id: "a-1", kind: "FORCE_COMPLETE", milestoneId: "m-2", status: "PENDING", reason: "Customer called in" }]}
      />,
      { wrapper: makeWrapper() },
    );
    fireEvent.click(screen.getByRole("button", { name: /testing/i }));

    expect(screen.getByText("Customer called in")).not.toBeNull();
    expect(screen.queryByRole("button", { name: /force complete/i })).toBeNull();
  });

  it("renders the dependencies and an empty comments state in the expanded panel", () => {
    renderRow(blocked);
    fireEvent.click(screen.getByRole("button", { name: /technical setup/i }));

    expect(screen.getByText("Dependencies")).not.toBeNull();
    expect(screen.getByText("Comments")).not.toBeNull();
    expect(screen.getByText(/comments aren't tracked here yet/i)).not.toBeNull();
  });

  it("shows open and total task counts on a milestone that has tasks", () => {
    renderRow({ ...active, taskSummary: { open: 2, total: 5 } });
    expect(screen.getByText("2 of 5 tasks open")).not.toBeNull();
  });

  it("renders no task count at all when a milestone has none", () => {
    renderRow({ ...active, taskSummary: { open: 0, total: 0 } });
    expect(screen.queryByText(/tasks open/)).toBeNull();
  });

  it("renders no task count when the roadmap carries no taskSummary at all", () => {
    renderRow(active);
    expect(screen.queryByText(/tasks open/)).toBeNull();
  });

  // Merged in from components/journey/__tests__/MilestoneRow.test.tsx (final
  // whole-branch review finding #6): this branch shipped two competing
  // test-file conventions, and MilestoneRow ended up with both a co-located
  // file (this one) and a __tests__/ one, added later by a task that did not
  // notice the first already existed. Consolidated here, the co-located
  // convention this file and tasks.test.tsx elsewhere already use; the
  // __tests__/ copy is deleted. No test case dropped in the merge.
  it("badges an internal-only milestone with a word, not only a colour", () => {
    renderRow({ ...active, portalVisible: false });
    expect(screen.getByText("Internal")).not.toBeNull();
  });

  it("adds no badge to a shared milestone", () => {
    renderRow({ ...active, portalVisible: true });
    expect(screen.queryByText("Internal")).toBeNull();
  });
});
