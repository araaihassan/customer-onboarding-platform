import { act } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import type { Me } from "@/lib/auth/types";

const logout = vi.fn(async () => {});
let user: Me | null = { fullName: "Maria Kessler", email: "maria@acme.test" };

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ user, logout }) }));
vi.mock("next-themes", () => ({ useTheme: () => ({ resolvedTheme: "light", setTheme: vi.fn() }) }));

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

function accountTrigger() {
  return screen.getByRole("button", { name: /account menu for maria kessler/i });
}

function signOut() {
  return screen.queryByRole("button", { name: /sign out/i });
}

beforeEach(() => {
  user = { fullName: "Maria Kessler", email: "maria@acme.test" };
  logout.mockClear();
});

afterEach(cleanup);

describe("TopBar", () => {
  it("shows the screen title a page has set", () => {
    renderTopBar("Customers");
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Customers");
  });

  it("shows the meta line only when a page supplies one", () => {
    const { unmount } = renderTopBar("Customers", "48 active");
    expect(screen.getByText("48 active")).not.toBeNull();
    unmount();

    renderTopBar("Customers");
    expect(screen.queryByText("48 active")).toBeNull();
  });

  /**
   * An <h1> with no text is an axe `empty-heading` violation, and there is always
   * at least one frame with no title — the page sets it from an effect, which runs
   * after this header has rendered.
   */
  it("renders no heading at all until a title exists", () => {
    render(
      <PageHeaderProvider>
        <TopBar />
        <PageWithoutHeader />
      </PageHeaderProvider>,
    );
    expect(screen.queryByRole("heading")).toBeNull();
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
    expect(screen.getByRole("heading", { level: 1 }).textContent).toBe("Customers");
    expect(screen.getByText("48 active")).not.toBeNull();

    rerender(<Harness withHeader={false} />);
    expect(screen.queryByRole("heading")).toBeNull();
    expect(screen.queryByText("48 active")).toBeNull();
  });

  it("keeps the account popover closed until it is opened", () => {
    renderTopBar();
    expect(accountTrigger().getAttribute("aria-expanded")).toBe("false");
    expect(signOut()).toBeNull();

    fireEvent.click(accountTrigger());
    expect(accountTrigger().getAttribute("aria-expanded")).toBe("true");
    expect(signOut()).not.toBeNull();
  });

  /**
   * Deliberately NOT the ARIA menu pattern: role="menu" only admits
   * menuitem/group/separator, so the identity block inside would be an
   * aria-required-children violation, and it would promise arrow-key navigation
   * a single action does not implement.
   */
  it("is a labelled popover, not an ARIA menu", () => {
    renderTopBar();
    fireEvent.click(accountTrigger());
    expect(screen.queryByRole("menu")).toBeNull();
    expect(screen.queryByRole("menuitem")).toBeNull();
    expect(screen.getByRole("group", { name: /account menu for maria kessler/i })).not.toBeNull();
  });

  it("signs out from the account popover", async () => {
    renderTopBar();
    fireEvent.click(accountTrigger());
    await act(async () => {
      fireEvent.click(signOut()!);
    });
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("moves focus into the popover on open", () => {
    renderTopBar();
    fireEvent.click(accountTrigger());
    expect(document.activeElement).toBe(signOut());
  });

  it("closes the account popover on Escape and returns focus to the trigger", () => {
    renderTopBar();
    const trigger = accountTrigger();
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(signOut()).toBeNull();
    expect(document.activeElement).toBe(trigger);
  });

  /**
   * Tab out must close it. Otherwise the popover floats over content the user has
   * moved on to, with their focus already somewhere behind it.
   */
  it("closes the account popover when focus leaves it", () => {
    renderTopBar();
    fireEvent.click(accountTrigger());
    const outside = document.createElement("button");
    document.body.appendChild(outside);

    fireEvent.blur(signOut()!, { relatedTarget: outside });

    expect(signOut()).toBeNull();
    outside.remove();
  });

  it("renders the theme toggle", () => {
    renderTopBar();
    expect(screen.getByRole("button", { name: /switch to dark theme/i })).not.toBeNull();
  });

  /**
   * Search and notifications are visual-only in the prototype. Shipping the
   * controls without the behaviour would be worse than omitting them.
   */
  it("ships no dead search or notification controls", () => {
    renderTopBar();
    expect(screen.queryByRole("searchbox")).toBeNull();
    expect(screen.queryByRole("button", { name: /notification/i })).toBeNull();
  });
});
