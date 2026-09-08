import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { Task } from "@/lib/api/tasks";

/**
 * `WorkBoard`'s responsive column collapse (Task 30, guarding against the same
 * failure class `Sidebar.test.tsx` guards: a Tailwind class assembled by
 * interpolating a variable INTO an arbitrary `min-[Npx]:`/`max-[Npx]:` bracket
 * is invisible to Tailwind's static source scan and compiles to no CSS at all
 * -- `min-[1024px]:flex${...}` shipped a sidebar that never rendered and passed
 * every review). jsdom evaluates neither media queries nor stacking contexts,
 * so -- exactly as that test does -- these assertions prove the STRUCTURAL
 * invariant: the expected named-breakpoint classes are present, and no class
 * anywhere in the rendered tree matches the unextractable arbitrary-bracket
 * pattern. That is the only thing this environment can actually prove; a real
 * browser's media query engine is what turns it into the collapse itself.
 *
 * Separate file from `WorkBoard.test.tsx` (Task 30's own brief) rather than
 * folded into it -- that suite's substance is bucket-placement logic, this
 * one's is layout structure, and the two shouldn't have to change together.
 */
const useMyWorkMock = vi.fn();
const useWorkContextsMock = vi.fn();

vi.mock("@/lib/api/tasks", () => ({
  useMyWork: (...args: unknown[]) => useMyWorkMock(...args),
  useWorkContexts: (...args: unknown[]) => useWorkContextsMock(...args),
}));

let searchParams = new URLSearchParams();
const replace = vi.fn();

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

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
  useMyWorkMock.mockReset();
  useWorkContextsMock.mockReset();
  useWorkContextsMock.mockReturnValue(new Map());
});

/** Every className string anywhere in the rendered subtree, elements without one excluded. */
function allClassNames(container: HTMLElement): string[] {
  return Array.from(container.querySelectorAll<HTMLElement>("*"))
    .map((el) => el.className)
    .filter((cls): cls is string => typeof cls === "string" && cls.length > 0);
}

describe("WorkBoard responsive collapse", () => {
  it("uses a flex column below lg, a 2-column grid at lg, and a 4-column grid at xl -- the full, unfiltered board", () => {
    useMyWorkMock.mockReturnValue({
      data: [task()],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    render(<WorkBoard />);

    const columns = screen.getByTestId("work-board-columns");

    // Below `lg` (1024px): a single flex column, one column rendered after
    // another -- never a grid track laid out beside another off-screen.
    expect(columns.className).toContain("flex");
    expect(columns.className).toContain("flex-col");

    // At `lg` and above: two columns.
    expect(columns.className).toContain("lg:grid");
    expect(columns.className).toContain("lg:grid-cols-2");

    // At `xl` (1280px) and above: all four.
    expect(columns.className).toContain("xl:grid-cols-4");
  });

  it("stays a single column at every width when the board is filtered to one bucket", () => {
    searchParams = new URLSearchParams("bucket=waiting");
    useMyWorkMock.mockReturnValue({
      data: [task({ status: "WAITING" })],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    render(<WorkBoard />);

    const columns = screen.getByTestId("work-board-columns");
    expect(columns.className).toContain("flex-col");
    expect(columns.className).not.toContain("grid-cols-2");
    expect(columns.className).not.toContain("grid-cols-4");
  });

  /**
   * The exact bug class this task exists to guard against: a class built by
   * interpolating a variable INTO an arbitrary bracket (`min-[1024px]:flex${...}`)
   * is unextractable by Tailwind's static scan and generates no CSS. Ruling
   * it out across the WHOLE rendered subtree -- not just `WorkBoard`'s own
   * outer container -- also covers `WorkColumn` and `TaskCard`, both mounted
   * here, so a regression introduced in either still fails this test.
   */
  it("never uses an arbitrary min-[Npx]:/max-[Npx]: breakpoint bracket anywhere in the board", () => {
    useMyWorkMock.mockReturnValue({
      data: [
        task({ id: "t-1", status: "PENDING" }),
        task({ id: "t-2", status: "IN_PROGRESS" }),
        task({ id: "t-3", status: "WAITING" }),
        task({ id: "t-4", status: "COMPLETED", completedAt: new Date().toISOString() }),
      ],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    const { container } = render(<WorkBoard />);

    const offenders = allClassNames(container).filter((cls) => /(?:min|max)-\[\d+px\]:/.test(cls));
    expect(offenders).toEqual([]);
  });

  it("never uses an arbitrary breakpoint bracket while loading or on error either", () => {
    useMyWorkMock.mockReturnValue({ data: undefined, isLoading: true, isError: false, refetch: vi.fn() });
    const { container: loading } = render(<WorkBoard />);
    expect(allClassNames(loading).filter((cls) => /(?:min|max)-\[\d+px\]:/.test(cls))).toEqual([]);
    cleanup();

    useMyWorkMock.mockReturnValue({ data: undefined, isLoading: false, isError: true, refetch: vi.fn() });
    const { container: errored } = render(<WorkBoard />);
    expect(allClassNames(errored).filter((cls) => /(?:min|max)-\[\d+px\]:/.test(cls))).toEqual([]);
  });
});
