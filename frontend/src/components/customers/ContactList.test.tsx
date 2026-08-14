import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Contact } from "@/lib/api/customers";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

const { ContactList } = await import("./ContactList");

const fetchMock = vi.fn();

const contacts: Contact[] = [
  {
    id: "p-1",
    customerId: "c-1",
    fullName: "Ada Okonjo",
    email: "ada@northwind.test",
    title: "Head of Operations",
    primaryContact: true,
    status: "ACTIVE",
  },
  {
    id: "p-2",
    customerId: "c-1",
    fullName: "Tom Reyes",
    email: "tom@northwind.test",
    primaryContact: false,
    status: "INACTIVE",
  },
];

function renderList(list: Contact[] = contacts) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ContactList customerId="c-1" contacts={list} />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = {};
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({
    ok: true,
    status: 204,
    text: async () => "",
    json: async () => undefined,
  } as unknown as Response);
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("ContactList", () => {
  it("lists each contact with the person's name and machine-readable address", () => {
    renderList();
    expect(screen.getByText("Ada Okonjo")).not.toBeNull();
    expect(screen.getByText("ada@northwind.test")).not.toBeNull();
    expect(screen.getByText("Head of Operations")).not.toBeNull();
  });

  /**
   * Circular means a person. Rounded-square means a company, and a contact is
   * never a company — the distinction is the only thing carrying that meaning.
   */
  it("marks a contact with a circular avatar", () => {
    const { container } = renderList();
    const avatar = container.querySelector<HTMLElement>("[aria-hidden='true']")!;
    expect(avatar.style.borderRadius).toBe("var(--ob-radius-full)");
  });

  it("marks the primary contact with a word, not only a colour", () => {
    renderList();
    expect(screen.getByText("Primary")).not.toBeNull();
  });

  it("hides the invitation action from someone without invitation.send", () => {
    renderList();
    expect(screen.queryByRole("button", { name: /send invitation/i })).toBeNull();
  });

  it("offers an invitation per contact to someone holding invitation.send", () => {
    permissions = { "invitation.send": ["ALL"] };
    renderList();

    // Named per contact: two buttons reading "Send invitation" would be
    // indistinguishable to anyone navigating by control.
    expect(screen.getByRole("button", { name: "Send invitation to Ada Okonjo" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Send invitation to Tom Reyes" })).not.toBeNull();
  });

  it("sends the invitation, confirms it, and stops offering it", async () => {
    permissions = { "invitation.send": ["ALL"] };
    renderList();

    fireEvent.click(screen.getByRole("button", { name: "Send invitation to Ada Okonjo" }));

    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Invitation sent"));
    expect(fetchMock.mock.calls.at(-1)![0]).toBe("/api/t/acme/customers/c-1/contacts/p-1/invitations");

    // Spent: an invitation already on its way is not offered again, and the
    // other contact's button is untouched.
    expect(screen.queryByRole("button", { name: "Send invitation to Ada Okonjo" })).toBeNull();
    expect(screen.getByRole("button", { name: "Send invitation to Tom Reyes" })).not.toBeNull();
  });

  /**
   * A failed invitation that says nothing leaves the sender believing it went
   * out — and nobody chases an invitation they think was sent.
   */
  it("reports a failed invitation instead of falling silent", async () => {
    permissions = { "invitation.send": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "boom",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    fireEvent.click(screen.getByRole("button", { name: "Send invitation to Ada Okonjo" }));

    await waitFor(() => expect(screen.getByRole("status").textContent).toBe("Something went wrong"));
    // Still offered, because it did not happen.
    expect(screen.getByRole("button", { name: "Send invitation to Ada Okonjo" })).not.toBeNull();
  });

  it("explains an empty contact list rather than showing nothing", () => {
    renderList([]);
    expect(screen.getByText("No contacts yet")).not.toBeNull();
  });
});
