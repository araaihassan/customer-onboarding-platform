import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Task } from "@/lib/api/tasks";
import { TaskDetail } from "./TaskDetail";

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

const task: Task = {
  id: "t-1",
  caseId: "case-1",
  milestoneId: "m-1",
  title: "Onboard the customer",
  description: "Collect the signed agreement and confirm banking details.",
  status: "PENDING",
  priority: "MEDIUM",
  dueDate: "2026-12-01",
};

// Mutable, and reset per test, so a PUT toggle's effect is visible to the
// GET refetch `useToggleChecklistItem`'s own cache invalidation triggers --
// a static fixture would have the refetch silently revert what the PUT just
// changed, which would be a bug in this mock, not in ChecklistEditor.
let items: { id: string; taskId: string; label: string; done: boolean; ordinal: number }[] = [];

beforeEach(() => {
  permissions = { "task.manage": ["ALL"], "task.complete": ["ALL"] };
  items = [
    { id: "c-1", taskId: "t-1", label: "Verify passport", done: false, ordinal: 0 },
    { id: "c-2", taskId: "t-1", label: "Confirm banking details", done: false, ordinal: 1 },
  ];
  fetchMock.mockReset();
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (url.includes("/checklist/") && init?.method === "PUT") {
      const itemId = url.split("/checklist/")[1];
      const item = items.find((i) => i.id === itemId)!;
      item.done = !item.done;
      return Promise.resolve(reply({ ...item }));
    }
    if (url.endsWith("/checklist")) return Promise.resolve(reply(items));
    return Promise.resolve(reply({}));
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderDetail(overrides: Partial<Task> = {}) {
  return render(<TaskDetail task={{ ...task, ...overrides }} />, { wrapper: makeWrapper() });
}

describe("TaskDetail", () => {
  it("renders the task's checklist through ChecklistEditor", async () => {
    renderDetail();

    await waitFor(() => expect(screen.getByText("Verify passport")).not.toBeNull());
    expect(screen.getByText("Confirm banking details")).not.toBeNull();
    expect(screen.getByText("Onboard the customer")).not.toBeNull();
    expect(screen.getByText(task.description!)).not.toBeNull();
  });

  /**
   * The backend rule from Task 22 (`ChecklistService.toggle` never touches
   * `Task.status`), asserted again at the UI so the two layers cannot drift.
   * Ticking every checklist item must never call the status-change endpoint,
   * and the task's own displayed status must stay exactly what it was.
   */
  it("does not complete the task when every checklist item is ticked", async () => {
    renderDetail();

    await waitFor(() => expect(screen.getByText("Verify passport")).not.toBeNull());

    fireEvent.click(screen.getByRole("button", { name: "Verify passport" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Verify passport" }).getAttribute("aria-pressed")).toBe("true"),
    );

    fireEvent.click(screen.getByRole("button", { name: "Confirm banking details" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: "Confirm banking details" }).getAttribute("aria-pressed")).toBe(
        "true",
      ),
    );

    // Still Pending -- the status control's own selection is untouched, and
    // nothing here ever called the task status endpoint.
    expect((screen.getByRole("combobox", { name: "Status" }) as HTMLSelectElement).value).toBe("PENDING");
    expect(
      fetchMock.mock.calls.some((call: unknown[]) => (call[0] as string).endsWith("/tasks/t-1/status")),
    ).toBe(false);
  });

  it("shows an em dash and 'Unassigned' when the task has no due date or assignee", async () => {
    renderDetail({ dueDate: undefined, assigneeId: undefined });

    await waitFor(() => expect(screen.getByText("Verify passport")).not.toBeNull());
    expect(screen.getByText("—")).not.toBeNull();
    expect(screen.getByText("Unassigned")).not.toBeNull();
  });
});
