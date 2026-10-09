import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ user: { userType: "INTERNAL" } }) }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));
vi.mock("@/lib/api/notifications", () => ({
  useUnreadCount: () => ({ data: 3 }),
  useMarkAllRead: () => ({ mutate: vi.fn() }),
  useMarkRead: () => ({ mutate: vi.fn() }),
  useInbox: () => ({ data: { pages: [{ items: [] }] }, isLoading: false, isError: false, hasNextPage: false }),
  usePreferences: () => ({ data: undefined, isLoading: true, isError: false }),
  useUpdatePreferences: () => ({ mutate: vi.fn() }),
}));

const { TopBar } = await import("./TopBar");
const { PageHeaderProvider, useSetPageHeader } = await import("./PageHeader");

function Page({ title, meta }: { title: string; meta?: string }) {
  useSetPageHeader(title, meta);
  return null;
}

/** A page that forgets the hook — an error branch, a loading branch, an oversight. */
function PageWithoutHeader() {
  return null;
}

function renderTopBar(title = "Customers", meta?: string) {
  return render(
    <PageHeaderProvider>
      <TopBar />
      <Page title={title} meta={meta} />
    </PageHeaderProvider>,
  );
}

afterEach(cleanup);

describe("TopBar", () => {
  it("shows the screen title a page has set, as an uppercase mono breadcrumb", () => {
    renderTopBar("Customers");
    expect(screen.getByText("Customers")).not.toBeNull();
  });

  it("shows the meta line only when a page supplies one", () => {
    const { unmount } = renderTopBar("Customers", "48 active");
    expect(screen.getByText("48 active")).not.toBeNull();
    unmount();

    renderTopBar("Customers");
    expect(screen.queryByText("48 active")).toBeNull();
  });

  it("renders no title at all until a page sets one", () => {
    render(
      <PageHeaderProvider>
        <TopBar />
        <PageWithoutHeader />
      </PageHeaderProvider>,
    );
    expect(screen.queryByText("Customers")).toBeNull();
  });

  /**
   * The regression this guards: header state lives in the provider ABOVE the
   * router outlet, so it survives navigation. Moving to a page that sets no
   * header must not leave the previous screen's title announcing the new one.
   */
  it("drops the title when navigating to a page that sets no header", () => {
    function Harness({ withHeader }: { withHeader: boolean }) {
      return (
        <PageHeaderProvider>
          <TopBar />
          {withHeader ? <Page title="Customers" meta="48 active" /> : <PageWithoutHeader />}
        </PageHeaderProvider>
      );
    }

    const { rerender } = render(<Harness withHeader />);
    expect(screen.getByText("Customers")).not.toBeNull();
    expect(screen.getByText("48 active")).not.toBeNull();

    rerender(<Harness withHeader={false} />);
    expect(screen.queryByText("Customers")).toBeNull();
    expect(screen.queryByText("48 active")).toBeNull();
  });

  /**
   * Search is visual-only in the prototype and the account control moved to
   * Rail -- neither belongs here. The inbox is real (6B): its control must
   * exist AND open the drawer, not merely be absent of dead ones.
   */
  it("ships a live Inbox control and no dead search or account controls", () => {
    renderTopBar();
    fireEvent.click(screen.getByRole("button", { name: /inbox/i }));
    const dialog = screen.getByRole("dialog", { name: "Inbox" });
    expect(dialog).not.toBeNull();
    // The header is sticky + z-30, a stacking context: a drawer inside it could
    // never rise above the Rail (z-60). It must be portalled out.
    expect(screen.getByRole("banner")).not.toContainElement(dialog);
    expect(screen.queryByRole("searchbox")).toBeNull();
    expect(screen.queryByRole("button", { name: /account/i })).toBeNull();
  });
});
