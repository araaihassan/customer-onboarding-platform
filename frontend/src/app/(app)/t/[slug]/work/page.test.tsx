import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

/**
 * The route is deliberately thin (same shape as dashboard/page.tsx): its own
 * job is wiring the shell header and mounting `WorkBoard`, which
 * WorkBoard.test.tsx already covers in depth. This proves only the wiring.
 */
const useMyWorkMock = vi.fn();
const useWorkContextsMock = vi.fn();

vi.mock("@/lib/api/tasks", () => ({
  useMyWork: (...args: unknown[]) => useMyWorkMock(...args),
  useWorkContexts: (...args: unknown[]) => useWorkContextsMock(...args),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/t/acme/work",
  useRouter: () => ({ replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const { default: WorkPage } = await import("./page");
const { PageHeaderProvider, usePageHeader } = await import("@/components/shell/PageHeader");

afterEach(cleanup);

beforeEach(() => {
  useMyWorkMock.mockReset();
  useWorkContextsMock.mockReset();
  useMyWorkMock.mockReturnValue({ data: [], isLoading: false, isError: false, refetch: vi.fn() });
  useWorkContextsMock.mockReturnValue(new Map());
});

function HeaderProbe() {
  const { title } = usePageHeader();
  return <span data-testid="header-title">{title}</span>;
}

describe("WorkPage", () => {
  it("sets the shell header title and renders the board", () => {
    render(
      <PageHeaderProvider>
        <HeaderProbe />
        <WorkPage />
      </PageHeaderProvider>,
    );

    expect(screen.getByTestId("header-title").textContent).toBe("My work");
    expect(screen.getByText("Only what you can act on.")).not.toBeNull();
  });
});
