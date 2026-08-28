import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

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
   * Search and notifications are visual-only in the prototype, and the account
   * control moved to Rail — none of them belong here. Shipping a dead control
   * would be worse than omitting it.
   */
  it("ships no dead search, notification or account controls", () => {
    renderTopBar();
    expect(screen.queryByRole("searchbox")).toBeNull();
    expect(screen.queryByRole("button", { name: /notification/i })).toBeNull();
    expect(screen.queryByRole("button", { name: /account/i })).toBeNull();
  });
});
