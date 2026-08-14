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

function renderTopBar(title = "Customers", meta?: string) {
  return render(
    <PageHeaderProvider>
      <TopBar />
      <Page title={title} meta={meta} />
    </PageHeaderProvider>,
  );
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

  it("keeps the account menu closed until it is opened", () => {
    renderTopBar();
    const trigger = screen.getByRole("button", { name: /maria kessler/i });
    expect(trigger.getAttribute("aria-expanded")).toBe("false");
    expect(screen.queryByRole("menu")).toBeNull();

    fireEvent.click(trigger);
    expect(trigger.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByRole("menu")).not.toBeNull();
  });

  it("signs out from the account menu", async () => {
    renderTopBar();
    fireEvent.click(screen.getByRole("button", { name: /maria kessler/i }));
    await act(async () => {
      fireEvent.click(screen.getByRole("menuitem", { name: /sign out/i }));
    });
    expect(logout).toHaveBeenCalledTimes(1);
  });

  /** Escape closes and returns focus to the trigger — component-specs §2. */
  it("closes the account menu on Escape and returns focus to the trigger", () => {
    renderTopBar();
    const trigger = screen.getByRole("button", { name: /maria kessler/i });
    fireEvent.click(trigger);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(screen.queryByRole("menu")).toBeNull();
    expect(document.activeElement).toBe(trigger);
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
