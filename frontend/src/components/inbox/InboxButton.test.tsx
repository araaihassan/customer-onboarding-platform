import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

let userType = "INTERNAL";
let count = 3;
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ user: { userType } }) }));
vi.mock("@/lib/api/notifications", () => ({
  useUnreadCount: () => ({ data: count }),
  useMarkAllRead: () => ({ mutate: vi.fn() }),
}));
vi.mock("./InboxList", () => ({ InboxList: () => <div>LIST</div> }));
vi.mock("./PreferencesPane", () => ({ PreferencesPane: () => <div>PREFS</div> }));

const { InboxButton } = await import("./InboxButton");

beforeEach(() => {
  userType = "INTERNAL";
  count = 3;
});
afterEach(cleanup);

describe("InboxButton", () => {
  it("shows the label and the count badge; 99+ above 99; no badge at 0", () => {
    const { unmount } = render(<InboxButton />);
    expect(screen.getByText("Inbox")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
    unmount();
    count = 250;
    const second = render(<InboxButton />);
    expect(screen.getByText("99+")).toBeInTheDocument();
    second.unmount();
    count = 0;
    render(<InboxButton />);
    expect(screen.queryByText("0")).toBeNull();
    expect(screen.getByRole("button", { name: "Inbox" })).toBeInTheDocument();
  });

  it("opens the drawer on click and toggles it with Ctrl-J", () => {
    render(<InboxButton />);
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /inbox/i }));
    expect(screen.getByRole("dialog", { name: "Inbox" })).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "j", ctrlKey: true });
    expect(screen.queryByRole("dialog")).toBeNull();
    fireEvent.keyDown(document, { key: "j", ctrlKey: true });
    expect(screen.getByRole("dialog")).toBeInTheDocument();
  });

  it("renders nothing, and ignores the shortcut, for a portal user", () => {
    userType = "PORTAL";
    const { container } = render(<InboxButton />);
    expect(container).toBeEmptyDOMElement();
    fireEvent.keyDown(document, { key: "j", ctrlKey: true });
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
