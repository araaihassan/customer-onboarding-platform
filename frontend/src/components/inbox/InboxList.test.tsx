import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";

const push = vi.fn();
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }) }));

const mutate = vi.fn();
const fetchNextPage = vi.fn();
const refetch = vi.fn();
let inbox: Record<string, unknown>;
vi.mock("@/lib/api/notifications", () => ({
  useInbox: () => inbox,
  useMarkRead: () => ({ mutate }),
}));

const { InboxList } = await import("./InboxList");

const n = (id: string, read: boolean) => ({
  id, type: "NEW_COMMENT", title: `Title ${id}`, body: `Body ${id}`, linkPath: `/cases/${id}`,
  tone: "INFO", read, createdAt: new Date().toISOString(),
});

beforeEach(() => {
  inbox = {
    data: { pages: [{ items: [n("a", false), n("b", true)] }, { items: [n("c", false)] }] },
    isLoading: false, isError: false, hasNextPage: true, isFetchingNextPage: false, fetchNextPage, refetch,
  };
});
afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("InboxList", () => {
  it("renders rows across pages in order", () => {
    render(<InboxList onNavigate={() => {}} />);
    const titles = screen.getAllByText(/^Title /).map((e) => e.textContent);
    expect(titles).toEqual(["Title a", "Title b", "Title c"]);
  });

  it("loads more while there is a next page", () => {
    render(<InboxList onNavigate={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it("hides Load more on the last page", () => {
    inbox.hasNextPage = false;
    render(<InboxList onNavigate={() => {}} />);
    expect(screen.queryByRole("button", { name: "Load more" })).toBeNull();
  });

  it("opening an unread row marks it read, navigates and closes", () => {
    const onNavigate = vi.fn();
    render(<InboxList onNavigate={onNavigate} />);
    fireEvent.click(screen.getByText("Title a"));
    expect(mutate).toHaveBeenCalledWith("a");
    expect(push).toHaveBeenCalledWith("/cases/a");
    expect(onNavigate).toHaveBeenCalled();
  });

  it("opening a read row does not mark it read", () => {
    render(<InboxList onNavigate={() => {}} />);
    fireEvent.click(screen.getByText("Title b"));
    expect(mutate).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalledWith("/cases/b");
  });

  it.each([
    ["an absolute URL", "https://evil.example/phish"],
    ["a protocol-relative URL", "//evil.example/phish"],
    ["a backslash protocol-relative URL", "/\\evil.example/phish"],
    ["a javascript: URL", "javascript:alert(1)"],
    ["a relative path without a leading slash", "cases/a"],
  ])("never navigates to %s, but still marks the row read and closes", (_label, linkPath) => {
    inbox.data = { pages: [{ items: [{ ...n("x", false), linkPath }] }] };
    const onNavigate = vi.fn();
    render(<InboxList onNavigate={onNavigate} />);
    fireEvent.click(screen.getByText("Title x"));
    expect(push).not.toHaveBeenCalled();
    expect(mutate).toHaveBeenCalledWith("x");
    expect(onNavigate).toHaveBeenCalled();
  });

  it("shows the empty state", () => {
    inbox.data = { pages: [{ items: [] }] };
    inbox.hasNextPage = false;
    render(<InboxList onNavigate={() => {}} />);
    expect(screen.getByText("You're all caught up.")).toBeInTheDocument();
  });

  it("shows skeletons while loading", () => {
    inbox = { ...inbox, data: undefined, isLoading: true };
    render(<InboxList onNavigate={() => {}} />);
    expect(screen.getByLabelText("Loading")).toBeInTheDocument();
  });

  it("shows an error whose retry refetches", () => {
    inbox = { ...inbox, data: undefined, isError: true };
    render(<InboxList onNavigate={() => {}} />);
    expect(screen.getByRole("alert")).toHaveTextContent("Notifications could not be loaded.");
    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(refetch).toHaveBeenCalled();
  });
});
