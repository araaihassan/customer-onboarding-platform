import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Contact, Customer } from "@/lib/api/customers";

let permissions: Record<string, string[]> = {};

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme", id: "c-1" }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: CustomerDetailPage } = await import("./page");

const fetchMock = vi.fn();

/**
 * Ownership is not decoration: ownerUserId backs the ASSIGNED scope,
 * owningDepartmentId backs DEPARTMENT and owningTeamId backs TEAM. PUT replaces
 * every field, so any of them dropped from the request makes the record
 * invisible to everyone holding only that scope.
 */
const customer: Customer = {
  id: "c-1",
  displayName: "Northwind Foods",
  legalName: "Northwind Foods Holdings Ltd",
  status: "ACTIVE",
  industry: "Food & Beverage",
  country: "GB",
  ownerUserId: "u-7",
  owningDepartmentId: "d-3",
  owningTeamId: "t-2",
};

const contacts: Contact[] = [
  {
    id: "p-1",
    customerId: "c-1",
    fullName: "Ada Okonjo",
    email: "ada@northwind.test",
    primaryContact: true,
    // Deliberately not ACTIVE: the customer's own pill reads "Active", and a
    // contact sharing it would make an assertion about the customer's status
    // pass on the contact's instead.
    status: "INACTIVE",
  },
];

let customerStatus = 200;

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<CustomerDetailPage />, { wrapper: Wrapper });
}

function bodyOf(call: unknown[]): Record<string, unknown> {
  return JSON.parse((call[1] as RequestInit).body as string);
}

beforeEach(() => {
  permissions = { "customer.view": ["ALL"], "contact.view": ["ALL"] };
  customerStatus = 200;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.endsWith("/contacts")) return jsonReply(contacts);
    if (url.includes("/deactivate")) return jsonReply(undefined, 204);
    if (customerStatus !== 200) return jsonReply({ message: "not found" }, customerStatus);
    return jsonReply(customer);
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("CustomerDetailPage", () => {
  it("shows the customer summary", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(screen.getByText("Food & Beverage")).not.toBeNull();
    expect(screen.getByText("Active")).not.toBeNull();
  });

  /**
   * The shell owns the <h1> and sets it from the page header context. Page content
   * starts at <h2>; a second <h1> here would be a duplicate top-level heading on
   * every record screen.
   */
  it("starts its own content at h2", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(screen.queryByRole("heading", { level: 1 })).toBeNull();
    expect(screen.getAllByRole("heading", { level: 2 }).length).toBeGreaterThan(0);
  });

  /**
   * Out-of-scope and non-existent are the same 404 by design. Saying "you don't
   * have access" would reintroduce exactly the distinction the 404 exists to
   * hide — it tells an attacker the record is real.
   */
  it("renders Not found for a 404 and never mentions access", async () => {
    customerStatus = 404;
    renderPage();

    await waitFor(() => expect(screen.getByText("Not found")).not.toBeNull());
    expect(document.body.textContent).not.toMatch(/access|permission|forbidden|not allowed/i);
  });

  /**
   * A server error is not a missing record. Collapsing every failure into "Not
   * found" tells the user their customer is gone when the truth is that the
   * server fell over — and hides the outage from whoever is watching the screen.
   */
  it("distinguishes a server error from a missing record", async () => {
    customerStatus = 500;
    renderPage();

    await waitFor(() => expect(screen.getByText("Something went wrong")).not.toBeNull());
    expect(screen.queryByText("Not found")).toBeNull();
  });

  it("hides the edit affordance from someone without customer.edit", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
  });

  it("offers the edit affordance to someone holding customer.edit", async () => {
    permissions = { "customer.view": ["ALL"], "customer.edit": ["ALL"] };
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit" })).not.toBeNull());
  });

  it("preserves the ownership fields the scope predicates read when saving an edit", async () => {
    permissions = { "customer.view": ["ALL"], "customer.edit": ["ALL"] };
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Edit" }));
    fireEvent.change(screen.getByLabelText("Display name"), { target: { value: "Northwind" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => (c[1] as RequestInit)?.method === "PUT")).toBe(true),
    );
    const put = fetchMock.mock.calls.find((c) => (c[1] as RequestInit)?.method === "PUT")!;
    expect(bodyOf(put)).toMatchObject({
      displayName: "Northwind",
      ownerUserId: "u-7",
      owningDepartmentId: "d-3",
      owningTeamId: "t-2",
    });
  });

  it("hides deactivation from someone without customer.deactivate", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(screen.queryByRole("button", { name: "Deactivate" })).toBeNull();
  });

  it("confirms deactivation by name before sending it", async () => {
    permissions = { "customer.view": ["ALL"], "customer.deactivate": ["ALL"] };
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Deactivate" }));

    const dialog = screen.getByRole("dialog", { name: "Deactivate customer" });
    expect(within(dialog).getByText("Deactivate Northwind Foods? This cannot be undone.")).not.toBeNull();
    expect(fetchMock.mock.calls.some((c) => (c[0] as string).includes("/deactivate"))).toBe(false);

    fireEvent.click(within(dialog).getByRole("button", { name: "Confirm" }));
    await waitFor(() =>
      expect(fetchMock.mock.calls.some((c) => (c[0] as string).includes("/deactivate"))).toBe(true),
    );
  });

  it("abandons deactivation when the dialog is cancelled", async () => {
    permissions = { "customer.view": ["ALL"], "customer.deactivate": ["ALL"] };
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Deactivate" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).toBeNull();
    expect(fetchMock.mock.calls.some((c) => (c[0] as string).includes("/deactivate"))).toBe(false);
  });

  /** Business records are deactivated, never deleted. */
  it("offers no delete action", async () => {
    permissions = {
      "customer.view": ["ALL"],
      "customer.edit": ["ALL"],
      "customer.deactivate": ["ALL"],
    };
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(screen.queryByRole("button", { name: /delete|remove/i })).toBeNull();
  });

  it("shows the contact list to someone holding contact.view", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Ada Okonjo")).not.toBeNull());
  });

  it("does not ask for contacts at all without contact.view", async () => {
    permissions = { "customer.view": ["ALL"] };
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods Holdings Ltd")).not.toBeNull());
    expect(fetchMock.mock.calls.some((c) => (c[0] as string).endsWith("/contacts"))).toBe(false);
  });
});
