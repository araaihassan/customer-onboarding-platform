import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
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

function renderList(
  list: Contact[] = contacts,
  extra: { isError?: boolean; onRetry?: () => void } = {},
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ContactList customerId="c-1" contacts={list} {...extra} />, { wrapper: Wrapper });
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

/**
 * Spec §12's definition of done reads "customers and contacts can be created and
 * invited". Until Task R2 the middle third had no interface at all: the endpoint
 * was built, gated and tested in Task 21, and nothing ever called it.
 *
 * `contact.manage` is the gate, read off CustomerContactService.create — NOT
 * `invitation.send`, which gates only the button beside it.
 */
describe("ContactList: adding a contact", () => {
  function openDialog() {
    fireEvent.click(screen.getByRole("button", { name: "Add contact" }));
    return screen.getByRole("dialog", { name: "Add contact" });
  }

  function fillAndSubmit(dialog: HTMLElement) {
    fireEvent.change(within(dialog).getByLabelText("Full name"), {
      target: { value: "Ada Okonjo" },
    });
    fireEvent.change(within(dialog).getByLabelText("Email"), {
      target: { value: "ada@northwind.test" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create contact" }));
  }

  it("hides the action from someone without contact.manage", () => {
    permissions = { "invitation.send": ["ALL"] };
    renderList();
    expect(screen.queryByRole("button", { name: "Add contact" })).toBeNull();
  });

  it("offers the action to someone holding contact.manage", () => {
    permissions = { "contact.manage": ["ALL"] };
    renderList();
    expect(screen.getByRole("button", { name: "Add contact" })).not.toBeNull();
  });

  it("posts the new contact to the customer it belongs to and closes the dialog", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: true,
      status: 201,
      text: async () => JSON.stringify({ id: "p-9" }),
      json: async () => ({ id: "p-9" }),
    } as unknown as Response);
    renderList();

    fillAndSubmit(openDialog());

    await waitFor(() =>
      expect(fetchMock.mock.calls.at(-1)![0]).toBe("/api/t/acme/customers/c-1/contacts"),
    );
    expect((fetchMock.mock.calls.at(-1)![1] as RequestInit).method).toBe("POST");
    expect(JSON.parse((fetchMock.mock.calls.at(-1)![1] as RequestInit).body as string)).toMatchObject({
      fullName: "Ada Okonjo",
      email: "ada@northwind.test",
    });

    // Success is visible: the dialog goes, and the list says so out loud.
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByRole("status").textContent).toBe("Contact added");
  });

  /**
   * The failure this project has shipped three times and caught three times in
   * review: an async surface whose error says nothing. A create that fails
   * silently leaves the dialog sitting open with no explanation, and the user
   * either presses the button again or walks away believing it worked.
   */
  it("reports a failed create instead of falling silent", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "boom",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    const dialog = openDialog();
    fillAndSubmit(dialog);

    await waitFor(() =>
      expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toBe(
        "Something went wrong",
      ),
    );
    // Still open and still offering the action, because it did not happen.
    expect(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Create contact" }),
    ).not.toBeNull();
  });

  /**
   * An error that outlives its cause trains people to ignore errors, so the
   * mutation is reset when the dialog opens rather than when it closes.
   */
  it("does not show a stale error when the dialog is reopened", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "boom",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    fillAndSubmit(openDialog());
    await waitFor(() => expect(screen.getByRole("alert")).not.toBeNull());

    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    openDialog();

    expect(screen.queryByRole("alert")).toBeNull();
  });

  /** Business records are deactivated, never deleted — and there is no endpoint. */
  it("offers no way to delete a contact", () => {
    permissions = { "contact.manage": ["ALL"] };
    renderList();
    expect(screen.queryByRole("button", { name: /delete|remove/i })).toBeNull();
  });

  /**
   * UNIQUE (customer_id, email) is a foreseeable user error, and "Something went
   * wrong" for it sends the user looking for a server fault instead of at the one
   * field they can fix. The backend now answers 409 rather than 500.
   */
  it("names a duplicate address rather than reporting a generic failure", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 409,
      text: async () => "conflict",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    fillAndSubmit(openDialog());

    await waitFor(() =>
      expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toBe(
        "A contact with this email address already exists for this customer",
      ),
    );
  });
});

/**
 * Editing, added in the Task R2 extension.
 *
 * PUT /contacts/{contactId} had existed since Task 21 with nothing calling it —
 * the same gap as creation, one endpoint over, and worse in consequence: with no
 * edit path a contact once created was permanent AND immutable, since
 * status = INACTIVE is the only retirement a contact has.
 */
describe("ContactList: editing a contact", () => {
  function openEdit(name = "Ada Okonjo") {
    fireEvent.click(screen.getByRole("button", { name: `Edit ${name}` }));
    return screen.getByRole("dialog", { name: "Edit contact" });
  }

  function lastBody(): Record<string, unknown> {
    return JSON.parse((fetchMock.mock.calls.at(-1)![1] as RequestInit).body as string);
  }

  it("hides the action from someone without contact.manage", () => {
    permissions = { "invitation.send": ["ALL"] };
    renderList();
    expect(screen.queryByRole("button", { name: /^Edit / })).toBeNull();
  });

  /**
   * Named per contact for the same reason the invitation button is: two rows both
   * offering "Edit" are indistinguishable to anyone navigating by control.
   */
  it("offers an edit action per contact, named for the person", () => {
    permissions = { "contact.manage": ["ALL"] };
    renderList();
    expect(screen.getByRole("button", { name: "Edit Ada Okonjo" })).not.toBeNull();
    expect(screen.getByRole("button", { name: "Edit Tom Reyes" })).not.toBeNull();
  });

  it("opens prefilled with that contact, not another", () => {
    permissions = { "contact.manage": ["ALL"] };
    renderList();

    const dialog = openEdit("Tom Reyes");
    expect((within(dialog).getByLabelText("Full name") as HTMLInputElement).value).toBe("Tom Reyes");
    expect((within(dialog).getByLabelText("Email") as HTMLInputElement).value).toBe(
      "tom@northwind.test",
    );
  });

  it("PUTs to the contact under its customer with every field the replace accepts", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: "p-1" }),
      json: async () => ({ id: "p-1" }),
    } as unknown as Response);
    renderList();

    const dialog = openEdit();
    fireEvent.change(within(dialog).getByLabelText("Job title"), {
      target: { value: "Chief Operating Officer" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(fetchMock.mock.calls.at(-1)![0]).toBe("/api/t/acme/customers/c-1/contacts/p-1"),
    );
    expect((fetchMock.mock.calls.at(-1)![1] as RequestInit).method).toBe("PUT");
    expect(lastBody()).toEqual({
      fullName: "Ada Okonjo",
      email: "ada@northwind.test",
      title: "Chief Operating Officer",
      phone: "",
      primaryContact: true,
      status: "ACTIVE",
    });

    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(screen.getByRole("status").textContent).toBe("Contact updated");
  });

  /** The only retirement a contact has, driven end to end. */
  it("retires a contact by setting it INACTIVE", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ id: "p-1" }),
      json: async () => ({ id: "p-1" }),
    } as unknown as Response);
    renderList();

    const dialog = openEdit();
    fireEvent.change(within(dialog).getByLabelText("Status"), { target: { value: "INACTIVE" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => expect(lastBody().status).toBe("INACTIVE"));
  });

  it("reports a failed save instead of falling silent", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      text: async () => "boom",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    const dialog = openEdit();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toBe(
        "Something went wrong",
      ),
    );
  });

  /**
   * Out-of-scope and non-existent are the same 404 by design. Saying "you don't
   * have access" here would hand back the fact the 404 exists to withhold.
   */
  it("renders Not found for a 404 and never mentions access", async () => {
    permissions = { "contact.manage": ["ALL"] };
    fetchMock.mockResolvedValue({
      ok: false,
      status: 404,
      text: async () => "not found",
      json: async () => ({}),
    } as unknown as Response);
    renderList();

    const dialog = openEdit();
    fireEvent.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(within(screen.getByRole("dialog")).getByRole("alert").textContent).toBe("Not found"),
    );
    expect(document.body.textContent).not.toMatch(/access|permission|forbidden|not allowed/i);
  });
});

/**
 * A failed READ, which is the fourth async surface in this project found saying
 * nothing on failure.
 *
 * Pre-existing from Task 27, but Task R2 changed its consequence: the Add contact
 * button now sits beside a list that renders "No contacts yet" when the fetch
 * failed, so the user's natural next step is to add someone who is already there
 * and collide with the duplicate-address 409.
 */
describe("ContactList: a failed read", () => {
  it("reports the failure instead of claiming there are no contacts", () => {
    renderList([], { isError: true });

    expect(screen.getByText("Something went wrong")).not.toBeNull();
    // The lie this replaces.
    expect(screen.queryByText("No contacts yet")).toBeNull();
  });

  it("offers a way out rather than a dead end", () => {
    const onRetry = vi.fn();
    renderList([], { isError: true, onRetry });

    fireEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalled();
  });

  /** "0" beside a list that failed to load is a count asserted from no data. */
  it("claims no count when the list did not load", () => {
    renderList([], { isError: true });
    expect(screen.queryByText("0")).toBeNull();
  });
});

describe("ContactList: a contact with no id", () => {
  /**
   * `ContactView.id` is optional in the generated types. The invitation button
   * already disables itself on a missing id; the edit button used to send
   * `contact.id ?? ""`, which is a PUT to `…/contacts/` — a different endpoint
   * entirely.
   */
  it("disables the edit action, as the invitation action already does", () => {
    permissions = { "contact.manage": ["ALL"], "invitation.send": ["ALL"] };
    renderList([{ ...contacts[0], id: undefined }]);

    expect((screen.getByRole("button", { name: "Edit Ada Okonjo" }) as HTMLButtonElement).disabled)
      .toBe(true);
    expect(
      (screen.getByRole("button", { name: "Send invitation to Ada Okonjo" }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
  });
});
