import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import type { Task } from "@/lib/api/tasks";

/**
 * `useMyWork`/`useWorkContexts` are mocked entirely -- the substance of this
 * suite is `WorkBoard`'s own bucket-placement logic (which column a task
 * lands in, and the one deliberate exception to "group by status alone" --
 * see WorkBoard.tsx's own doc comment), not the network layer, which
 * tasks.test.tsx already covers for `useMyWork` and `useWorkContexts`
 * separately.
 */
const useMyWorkMock = vi.fn();
const useWorkContextsMock = vi.fn();

vi.mock("@/lib/api/tasks", () => ({
  useMyWork: (...args: unknown[]) => useMyWorkMock(...args),
  useWorkContexts: (...args: unknown[]) => useWorkContextsMock(...args),
}));

let searchParams = new URLSearchParams();
const replace = vi.fn((url: string) => {
  searchParams = new URLSearchParams(url.split("?")[1] ?? "");
});

vi.mock("next/navigation", () => ({
  usePathname: () => "/t/acme/work",
  useRouter: () => ({ replace }),
  useSearchParams: () => searchParams,
}));

const { WorkBoard } = await import("./WorkBoard");

afterEach(cleanup);

function task(overrides: Partial<Task> = {}): Task {
  return { id: "t-1", caseId: "case-1", title: "Task", status: "PENDING", ...overrides } as Task;
}

function mockWork(tasks: Task[] | undefined, overrides: Record<string, unknown> = {}) {
  useMyWorkMock.mockReturnValue({
    data: tasks,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
    ...overrides,
  });
}

function cardsIn(bucketId: string) {
  return within(screen.getByTestId(`work-column-${bucketId}`)).queryAllByTestId("task-card");
}

const ALL_BUCKETS = ["do_now", "in_progress", "waiting", "done_this_week"];

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
  useMyWorkMock.mockReset();
  useWorkContextsMock.mockReset();
  useWorkContextsMock.mockReturnValue(new Map());
});

describe("WorkBoard", () => {
  it("puts PENDING in Do now, IN_PROGRESS in In progress, and WAITING in its own column", () => {
    mockWork([
      task({ id: "t-pending", status: "PENDING" }),
      task({ id: "t-inprogress", status: "IN_PROGRESS" }),
      task({ id: "t-waiting", status: "WAITING" }),
    ]);
    render(<WorkBoard />);

    expect(cardsIn("do_now")).toHaveLength(1);
    expect(cardsIn("in_progress")).toHaveLength(1);
    expect(cardsIn("waiting")).toHaveLength(1);
    expect(cardsIn("done_this_week")).toHaveLength(0);
  });

  it("shows a COMPLETED task from 3 days ago in Done this week", () => {
    const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString();
    mockWork([task({ id: "t-recent", status: "COMPLETED", completedAt: threeDaysAgo })]);
    render(<WorkBoard />);

    expect(cardsIn("done_this_week")).toHaveLength(1);
  });

  /**
   * The real backend (`TaskService.bucketFilter`) never returns a COMPLETED
   * task this old from `myWork(null)` -- but this proves WorkBoard's own
   * defensive re-check (see WorkBoard.tsx's `BUCKETS` doc comment) rather
   * than trusting a hand-built mock's data unconditionally. A task this
   * stale must not surface in ANY column, not just be missing from Done
   * this week.
   */
  it("omits a COMPLETED task from 10 days ago, even though the mocked hook returns one", () => {
    const tenDaysAgo = new Date(Date.now() - 10 * 24 * 60 * 60 * 1000).toISOString();
    mockWork([task({ id: "t-stale", status: "COMPLETED", completedAt: tenDaysAgo })]);
    render(<WorkBoard />);

    for (const bucket of ALL_BUCKETS) {
      expect(cardsIn(bucket)).toHaveLength(0);
    }
  });

  it("omits CANCELLED tasks from every column", () => {
    mockWork([task({ id: "t-cancelled", status: "CANCELLED" })]);
    render(<WorkBoard />);

    for (const bucket of ALL_BUCKETS) {
      expect(cardsIn(bucket)).toHaveLength(0);
    }
  });

  it("renders each column's own empty state, not one shared board-level state", () => {
    mockWork([]);
    render(<WorkBoard />);

    expect(screen.getByText("Nothing needs you right now")).not.toBeNull();
    expect(screen.getByText("Nothing in progress")).not.toBeNull();
    expect(screen.getByText("Nothing waiting on anyone")).not.toBeNull();
    expect(screen.getByText("Nothing completed this week yet")).not.toBeNull();
  });

  it("renders a loading skeleton while the query is in flight, not an empty board", () => {
    mockWork(undefined, { isLoading: true });
    const { container } = render(<WorkBoard />);

    expect(container.querySelectorAll('[aria-busy="true"]').length).toBeGreaterThan(0);
    expect(screen.queryByTestId("work-column-do_now")).toBeNull();
  });

  it("renders a retry control on error, which re-issues the query", () => {
    const refetch = vi.fn();
    mockWork(undefined, { isError: true, refetch });
    render(<WorkBoard />);

    fireEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(refetch).toHaveBeenCalledOnce();
  });

  it("calls useMyWork with no bucket filter by default -- one call, grouped client-side", () => {
    mockWork([]);
    render(<WorkBoard />);

    expect(useMyWorkMock).toHaveBeenCalledWith({});
  });

  it("passes each task's case name and customer name from useWorkContexts onto its card", () => {
    useWorkContextsMock.mockReturnValue(
      new Map([["case-1", { caseName: "Kickoff case", customerName: "Northwind Foods" }]]),
    );
    mockWork([task({ id: "t-1", caseId: "case-1", status: "PENDING" })]);
    render(<WorkBoard />);

    const column = screen.getByTestId("work-column-do_now");
    expect(within(column).getByText("Kickoff case")).not.toBeNull();
    expect(within(column).getByText("Northwind Foods")).not.toBeNull();
  });

  it("puts a bucket filter into the URL when a column chip is clicked, so the queue is shareable", () => {
    mockWork([]);
    render(<WorkBoard />);

    fireEvent.click(screen.getByRole("button", { name: "Waiting" }));
    expect(replace).toHaveBeenCalledWith("/t/acme/work?bucket=waiting");
  });

  it("reads an existing bucket param from the URL, narrows the query, and renders only that column", () => {
    searchParams = new URLSearchParams("bucket=in_progress");
    mockWork([task({ id: "t-1", status: "IN_PROGRESS" })]);
    render(<WorkBoard />);

    expect(useMyWorkMock).toHaveBeenCalledWith({ bucket: "in_progress" });
    expect(screen.queryByTestId("work-column-do_now")).toBeNull();
    expect(screen.getByTestId("work-column-in_progress")).not.toBeNull();
  });

  it("falls back to the full, unfiltered board on an invalid bucket param rather than erroring", () => {
    searchParams = new URLSearchParams("bucket=nonsense");
    mockWork([]);
    render(<WorkBoard />);

    expect(useMyWorkMock).toHaveBeenCalledWith({});
    expect(screen.getByTestId("work-column-do_now")).not.toBeNull();
  });
});
