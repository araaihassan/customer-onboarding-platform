import "@testing-library/jest-dom/vitest";
import { act } from "react";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Me } from "@/lib/auth/types";
import { Rail } from "./Rail";

const logout = vi.fn(async () => {});
let user: Me | null = { fullName: "Jordan Diaz", email: "jordan@acme.test" };

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ user, logout }) }));

function accountTrigger() {
  return screen.getByRole("button", { name: /jordan diaz/i });
}

function signOut() {
  return screen.queryByRole("button", { name: /sign out/i });
}

beforeEach(() => {
  user = { fullName: "Jordan Diaz", email: "jordan@acme.test" };
  logout.mockClear();
});

afterEach(cleanup);

describe("Rail", () => {
  it("renders the brand mark and the account trigger, and opens the account popover on click", () => {
    render(<Rail />);
    const trigger = screen.getByRole("button", { name: /jordan diaz/i });
    expect(trigger).toBeInTheDocument();

    fireEvent.click(trigger);
    expect(screen.getByText("Jordan Diaz")).toBeInTheDocument();
    expect(screen.getByText("jordan@acme.test")).toBeInTheDocument();
  });

  it("does not render a sidebar-toggle button when onToggleSidebar is omitted", () => {
    render(<Rail />);
    expect(screen.queryByRole("button", { name: /toggle navigation/i })).not.toBeInTheDocument();
  });

  it("renders and calls onToggleSidebar when provided", () => {
    const onToggle = vi.fn();
    render(<Rail onToggleSidebar={onToggle} />);
    fireEvent.click(screen.getByRole("button", { name: /toggle navigation/i }));
    expect(onToggle).toHaveBeenCalledOnce();
  });

  it("keeps the account popover closed until it is opened", () => {
    render(<Rail />);
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
    render(<Rail />);
    fireEvent.click(accountTrigger());
    expect(screen.queryByRole("menu")).toBeNull();
    expect(screen.queryByRole("menuitem")).toBeNull();
    expect(screen.getByRole("group", { name: /account menu for jordan diaz/i })).not.toBeNull();
  });

  it("signs out from the account popover", async () => {
    render(<Rail />);
    fireEvent.click(accountTrigger());
    await act(async () => {
      fireEvent.click(signOut()!);
    });
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("moves focus into the popover on open", () => {
    render(<Rail />);
    fireEvent.click(accountTrigger());
    expect(document.activeElement).toBe(signOut());
  });

  it("closes the account popover on Escape and returns focus to the trigger", () => {
    render(<Rail />);
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
    render(<Rail />);
    fireEvent.click(accountTrigger());
    const outside = document.createElement("button");
    document.body.appendChild(outside);

    fireEvent.blur(signOut()!, { relatedTarget: outside });

    expect(signOut()).toBeNull();
    outside.remove();
  });
});
