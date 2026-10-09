import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { createRef } from "react";

let unread = 3;
const markAll = vi.fn();
vi.mock("@/lib/api/notifications", () => ({
  useUnreadCount: () => ({ data: unread }),
  useMarkAllRead: () => ({ mutate: markAll }),
}));
vi.mock("./InboxList", () => ({ InboxList: () => <div>LIST PANE</div> }));
vi.mock("./PreferencesPane", () => ({ PreferencesPane: () => <div>PREFS PANE</div> }));

const { InboxDrawer } = await import("./InboxDrawer");

beforeEach(() => {
  unread = 3;
  markAll.mockClear();
});
afterEach(cleanup);

describe("InboxDrawer", () => {
  it("is a modal dialog labelled Inbox with the unread count", () => {
    render(<InboxDrawer onClose={vi.fn()} returnFocusTo={createRef()} />);
    const d = screen.getByRole("dialog", { name: "Inbox" });
    expect(d).toHaveAttribute("aria-modal", "true");
    expect(screen.getByText("3 UNREAD")).toBeInTheDocument();
  });

  it("marks all read, and hides the action at zero", () => {
    const { unmount } = render(<InboxDrawer onClose={vi.fn()} returnFocusTo={createRef()} />);
    fireEvent.click(screen.getByRole("button", { name: "Mark all read" }));
    expect(markAll).toHaveBeenCalledTimes(1);
    unmount();
    unread = 0;
    render(<InboxDrawer onClose={vi.fn()} returnFocusTo={createRef()} />);
    expect(screen.queryByRole("button", { name: "Mark all read" })).toBeNull();
  });

  it("toggles between the list and the preferences pane, flipping its label", () => {
    render(<InboxDrawer onClose={vi.fn()} returnFocusTo={createRef()} />);
    expect(screen.getByText("LIST PANE")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Preferences" }));
    expect(screen.getByText("PREFS PANE")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "← Notifications" }));
    expect(screen.getByText("LIST PANE")).toBeInTheDocument();
  });

  it("closes on the close button, Escape and a scrim click", () => {
    const onClose = vi.fn();
    const { container } = render(<InboxDrawer onClose={onClose} returnFocusTo={createRef()} />);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.keyDown(document, { key: "Escape" });
    fireEvent.click(container.querySelector("[data-inbox-scrim]")!);
    expect(onClose).toHaveBeenCalledTimes(3);
  });

  it("traps Tab inside the drawer", () => {
    render(<InboxDrawer onClose={vi.fn()} returnFocusTo={createRef()} />);
    const close = screen.getByRole("button", { name: "Close" });
    close.focus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Mark all read" }));
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(close);
  });

  it("returns focus to the trigger on unmount", () => {
    const trigger = document.createElement("button");
    document.body.appendChild(trigger);
    const ref = { current: trigger };
    const { unmount } = render(<InboxDrawer onClose={vi.fn()} returnFocusTo={ref} />);
    unmount();
    expect(document.activeElement).toBe(trigger);
    trigger.remove();
  });
});
