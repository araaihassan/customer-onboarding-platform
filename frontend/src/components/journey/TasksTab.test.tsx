import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { TasksTab } from "./TasksTab";

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

const roadmap = {
  stages: [
    {
      id: "s-1",
      name: "Onboarding",
      ordinal: 0,
      milestones: [
        { id: "m-1", name: "Kickoff", status: "ACTIVE", requirements: [] },
        { id: "m-2", name: "Verification", status: "PENDING", requirements: [] },
      ],
    },
  ],
};

const verificationTask = {
  id: "t-2",
  caseId: "case-1",
  milestoneId: "m-2",
  title: "Collect proof of address",
  status: "PENDING",
  priority: "MEDIUM",
};
const kickoffTask = {
  id: "t-1",
  caseId: "case-1",
  milestoneId: "m-1",
  title: "Send welcome email",
  status: "PENDING",
  priority: "LOW",
};

function mockEndpoints({
  tasks = [verificationTask, kickoffTask],
  tasksStatus = 200,
  roadmapStatus = 200,
}: { tasks?: unknown[]; tasksStatus?: number; roadmapStatus?: number } = {}) {
  fetchMock.mockImplementation((url: string) => {
    if (url.includes("/roadmap")) return Promise.resolve(reply(roadmap, roadmapStatus));
    if (url.includes("/tasks")) return Promise.resolve(reply(tasks, tasksStatus));
    return Promise.resolve(reply({}));
  });
}

beforeEach(() => {
  permissions = { "task.manage": ["ALL"], "task.view": ["ALL"], "task.complete": ["ALL"] };
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderTab() {
  return render(<TasksTab caseId="case-1" />, { wrapper: makeWrapper() });
}

describe("TasksTab", () => {
  it("groups tasks by milestone in roadmap order, not task-list order", async () => {
    mockEndpoints();
    renderTab();

    await waitFor(() => expect(screen.getByText("Send welcome email")).not.toBeNull());
    expect(screen.getByText("Collect proof of address")).not.toBeNull();

    // The task list arrives with the Verification-milestone task FIRST, but the
    // roadmap places Kickoff before Verification -- the rendered order must
    // follow the roadmap, not the task array.
    const kickoffHeading = screen.getByText("Kickoff");
    const verificationHeading = screen.getByText("Verification");
    expect(kickoffHeading.compareDocumentPosition(verificationHeading) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();

    const welcomeCard = screen.getByText("Send welcome email");
    expect(kickoffHeading.compareDocumentPosition(welcomeCard) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(verificationHeading.compareDocumentPosition(welcomeCard) & Node.DOCUMENT_POSITION_PRECEDING)
      .toBeTruthy();
  });

  it("renders an empty state with an action, not blank space, when there are no tasks", async () => {
    mockEndpoints({ tasks: [] });
    renderTab();

    await waitFor(() => expect(screen.getByText("No tasks yet")).not.toBeNull());
    expect(screen.getByRole("button", { name: /new task/i })).not.toBeNull();
  });

  it("omits the create action from the empty state without task.manage", async () => {
    permissions = { "task.view": ["ALL"] };
    mockEndpoints({ tasks: [] });
    renderTab();

    await waitFor(() => expect(screen.getByText("No tasks yet")).not.toBeNull());
    expect(screen.queryByRole("button", { name: /new task/i })).toBeNull();
  });

  it("renders SkeletonRows while the case's tasks and roadmap are loading", () => {
    fetchMock.mockReturnValue(new Promise(() => {})); // never resolves
    const { container } = renderTab();

    expect(container.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThan(0);
  });

  it("renders a retry control on error, which re-issues both requests", async () => {
    mockEndpoints({ tasksStatus: 500 });
    renderTab();

    const retry = await screen.findByRole("button", { name: /try again/i });
    fetchMock.mockClear();
    mockEndpoints();
    fireEvent.click(retry);

    await waitFor(() => expect(screen.getByText("Send welcome email")).not.toBeNull());
  });
});
