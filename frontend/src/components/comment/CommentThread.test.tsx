import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Participant } from "@/lib/api/cases";
import type { Comment } from "@/lib/api/comments";
import { CommentThread } from "./CommentThread";

let permissions: Record<string, string[]> = {};
let currentUserId = "u-1";
vi.mock("@/lib/auth/useAuth", () => ({
  useAuth: () => ({ permissions, user: { id: currentUserId } }),
}));

afterEach(cleanup);

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function makeWrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const participants: Participant[] = [
  { userId: "u-1", fullName: "Ada Lovelace" } as Participant,
  { userId: "u-2", fullName: "Grace Hopper" } as Participant,
];

const laterComment: Comment = {
  id: "c-2",
  caseId: "case-1",
  resourceType: "CASE",
  resourceId: "case-1",
  authorId: "u-2",
  body: "Second, written later",
  createdAt: "2026-09-01T10:00:00Z",
};
const earlierComment: Comment = {
  id: "c-1",
  caseId: "case-1",
  resourceType: "CASE",
  resourceId: "case-1",
  authorId: "u-1",
  body: "First, written earlier",
  createdAt: "2026-09-01T09:00:00Z",
};

// Deliberately returned out of createdAt order -- CommentService.forResource
// carries no ORDER BY, so the component's own oldest-first sort is what this
// asserts, not an assumption about server order.
const comments: Comment[] = [laterComment, earlierComment];

function renderThread(commentsOverride?: Comment[]) {
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === "POST" && url.includes("/comments")) return Promise.resolve(reply({}));
    if (url.includes("/comments")) {
      return Promise.resolve(reply(commentsOverride ?? comments));
    }
    return Promise.resolve(reply({}));
  });
  global.fetch = fetchMock as unknown as typeof fetch;

  return render(
    <CommentThread caseId="case-1" resourceType="CASE" resourceId="case-1" participants={participants} />,
    { wrapper: makeWrapper() },
  );
}

beforeEach(() => {
  permissions = { "comment.create": ["ALL"] };
  currentUserId = "u-1";
  fetchMock.mockReset();
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("CommentThread", () => {
  it("renders comments oldest-first, with author and a relative time", async () => {
    renderThread();

    await waitFor(() => expect(screen.getByText("First, written earlier")).not.toBeNull());
    expect(screen.getByText("Second, written later")).not.toBeNull();
    expect(screen.getByText("Ada Lovelace")).not.toBeNull();
    expect(screen.getByText("Grace Hopper")).not.toBeNull();

    const first = screen.getByText("First, written earlier");
    const second = screen.getByText("Second, written later");
    expect(first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("falls back to a shortened id when the author holds no participant record", async () => {
    renderThread([
      {
        id: "c-3",
        caseId: "case-1",
        resourceType: "CASE",
        resourceId: "case-1",
        authorId: "11111111-1111-1111-1111-222233334444",
        body: "From someone off the participant list",
        createdAt: "2026-09-01T11:00:00Z",
      },
    ]);

    await waitFor(() => expect(screen.getByText("From someone off the participant list")).not.toBeNull());
    expect(screen.getByText("222233334444")).not.toBeNull();
  });

  it("shows the edit control only on the actor's own comment", async () => {
    renderThread();

    await waitFor(() => expect(screen.getByText("First, written earlier")).not.toBeNull());

    // c-1's author is u-1 (the signed-in user); c-2's is u-2.
    expect(screen.getAllByRole("button", { name: "Edit" })).toHaveLength(1);
  });

  it("marks an edited comment", async () => {
    renderThread([{ ...earlierComment, editedAt: "2026-09-01T09:30:00Z" }, laterComment]);

    await waitFor(() => expect(screen.getByText("First, written earlier")).not.toBeNull());
    expect(screen.getByText((text) => text.includes("edited"))).not.toBeNull();
  });

  it("refuses an empty body before issuing a request", async () => {
    renderThread([]);
    await waitFor(() => expect(screen.getByText("No comments yet")).not.toBeNull());

    const submit = screen.getByRole("button", { name: "Post comment" });
    expect(submit.hasAttribute("disabled")).toBe(true);

    const textarea = screen.getByLabelText("Add a comment");
    fireEvent.change(textarea, { target: { value: "   " } });
    // Whitespace-only trims to empty -- still refused, same as the untouched field.
    expect(submit.hasAttribute("disabled")).toBe(true);

    const postCalls = () =>
      fetchMock.mock.calls.filter(
        (call: unknown[]) => (call[1] as RequestInit | undefined)?.method === "POST",
      );
    expect(postCalls()).toHaveLength(0);
  });

  it("keeps the typed draft when posting fails, instead of discarding it", async () => {
    renderThread([]);
    await waitFor(() => expect(screen.getByText("No comments yet")).not.toBeNull());

    fetchMock.mockImplementation((url: string, init?: RequestInit) => {
      if (init?.method === "POST" && url.includes("/comments")) {
        return Promise.resolve(reply({ title: "Server error" }, 500));
      }
      return Promise.resolve(reply(comments));
    });

    const textarea = screen.getByLabelText("Add a comment") as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "A draft that must survive a failed post" } });

    const submit = screen.getByRole("button", { name: "Post comment" });
    expect(submit.hasAttribute("disabled")).toBe(false);
    fireEvent.click(submit);

    // The mutation settles into an error state -- surfaced next to the field
    // (TextareaField marks it aria-invalid), never a top-of-page banner.
    await waitFor(() => expect(textarea.getAttribute("aria-invalid")).toBe("true"));
    expect(textarea.value).toBe("A draft that must survive a failed post");
  });

  it("posts and clears the draft on success", async () => {
    renderThread([]);
    await waitFor(() => expect(screen.getByText("No comments yet")).not.toBeNull());

    const textarea = screen.getByLabelText("Add a comment") as HTMLTextAreaElement;
    fireEvent.change(textarea, { target: { value: "A real comment" } });
    fireEvent.click(screen.getByRole("button", { name: "Post comment" }));

    await waitFor(() => expect(textarea.value).toBe(""));
  });

  // Final whole-branch review, issue 2: three seeded role templates (Sales
  // Representative, Service Provider, Business Partner) hold case.view but
  // not comment.create, so an actor with no comment.create at all must not
  // get a compose box that can only ever 403. All the other cases in this
  // file set comment.create -- this is the one that would have caught the
  // regression.
  it("does not render the composer when the actor holds no comment.create", async () => {
    permissions = {};
    renderThread([]);
    await waitFor(() => expect(screen.getByText("No comments yet")).not.toBeNull());

    expect(screen.queryByLabelText("Add a comment")).toBeNull();
    expect(screen.queryByRole("button", { name: "Post comment" })).toBeNull();
  });
});
