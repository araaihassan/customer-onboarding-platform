import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Customer } from "@/lib/api/customers";

let permissions: Record<string, string[]> = {};
const push = vi.fn();

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme" }),
  useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: CustomersPage } = await import("./page");

const fetchMock = vi.fn();

const northwind: Customer = {
  id: "0199a0c1-0000-7000-8000-00000000abcd",
  displayName: "Northwind Foods",
  legalName: "Northwind Foods Holdings Ltd",
  status: "ACTIVE",
  industry: "Food & Beverage",
  country: "GB",
};

/** Page payloads keyed by nothing more than "what the next list call returns". */
let listPayload: Record<string, unknown> = {
  content: [northwind],
  totalElements: 1,
  totalPages: 1,
  number: 0,
  first: true,
  last: true,
};

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
  return render(<CustomersPage />, { wrapper: Wrapper });
}

function listUrls(): string[] {
  return fetchMock.mock.calls
    .map((call) => call[0] as string)
    .filter((url) => url.includes("/customers?"));
}

beforeEach(() => {
  permissions = { "customer.view": ["ALL"] };
  push.mockClear();
  listPayload = {
    content: [northwind],
    totalElements: 1,
    totalPages: 1,
    number: 0,
    first: true,
    last: true,
  };
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return jsonReply({ id: "c-new" }, 201);
    return jsonReply(listPayload);
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("CustomersPage", () => {
  it("lists the customers it is given", async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByText("Northwind Foods").length).toBeGreaterThan(0));
  });

  /**
   * Hiding the affordance is a courtesy, not a control — the endpoint enforces
   * regardless. Both directions matter: a button that never disappears is the
   * same bug as one that never appears.
   */
  it("hides New customer from someone without customer.create", async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByText("Northwind Foods").length).toBeGreaterThan(0));
    expect(screen.queryByRole("button", { name: "New customer" })).toBeNull();
  });

  it("offers New customer to someone holding customer.create", async () => {
    permissions = { "customer.view": ["ALL"], "customer.create": ["ALL"] };
    renderPage();
    await waitFor(() => expect(screen.getByRole("button", { name: "New customer" })).not.toBeNull());
  });

  it("explains an empty list rather than showing an empty table", async () => {
    listPayload = { content: [], totalElements: 0, totalPages: 0, number: 0, first: true, last: true };
    renderPage();
    await waitFor(() => expect(screen.getByText("No customers yet")).not.toBeNull());
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("presents the status filters as a pressed-state group", async () => {
    renderPage();
    const group = await screen.findByRole("group", { name: "Filter by status" });
    const chips = within(group).getAllByRole("button");

    expect(chips.map((c) => c.textContent)).toEqual([
      "All",
      "Prospect",
      "Active",
      "On hold",
      "Inactive",
    ]);
    expect(within(group).getByRole("button", { name: "All" }).getAttribute("aria-pressed")).toBe("true");
    expect(within(group).getByRole("button", { name: "Active" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("filters by status and moves the pressed state with it", async () => {
    renderPage();
    const group = await screen.findByRole("group", { name: "Filter by status" });
    fireEvent.click(within(group).getByRole("button", { name: "On hold" }));

    await waitFor(() => expect(listUrls().at(-1)).toContain("status=ON_HOLD"));
    expect(within(group).getByRole("button", { name: "On hold" }).getAttribute("aria-pressed")).toBe("true");
    expect(within(group).getByRole("button", { name: "All" }).getAttribute("aria-pressed")).toBe("false");
  });

  it("searches on what was typed", async () => {
    renderPage();
    const box = await screen.findByRole("searchbox", { name: "Search customers" });
    fireEvent.change(box, { target: { value: "halden" } });

    await waitFor(() => expect(listUrls().at(-1)).toContain("search=halden"));
  });

  /**
   * Page 3 of a filter that no longer applies is an empty screen with no
   * explanation. Changing a filter returns to the first page.
   */
  it("returns to the first page when the filter changes", async () => {
    listPayload = {
      content: [northwind],
      totalElements: 60,
      totalPages: 3,
      number: 0,
      first: true,
      last: false,
    };
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "Next page" }));
    await waitFor(() => expect(listUrls().at(-1)).toContain("page=1"));

    const group = screen.getByRole("group", { name: "Filter by status" });
    fireEvent.click(within(group).getByRole("button", { name: "Active" }));
    await waitFor(() => expect(listUrls().at(-1)).toContain("page=0"));
  });

  it("does not offer pagination when everything fits on one page", async () => {
    renderPage();
    await waitFor(() => expect(screen.getAllByText("Northwind Foods").length).toBeGreaterThan(0));
    expect(screen.queryByRole("button", { name: "Next page" })).toBeNull();
  });

  it("creates a customer and navigates to the record it created", async () => {
    permissions = { "customer.view": ["ALL"], "customer.create": ["ALL"] };
    renderPage();

    fireEvent.click(await screen.findByRole("button", { name: "New customer" }));
    const dialog = screen.getByRole("dialog", { name: "New customer" });

    fireEvent.change(within(dialog).getByLabelText("Display name"), {
      target: { value: "Halden Rail" },
    });
    fireEvent.change(within(dialog).getByLabelText("Legal name"), {
      target: { value: "Halden Rail AS" },
    });
    fireEvent.click(within(dialog).getByRole("button", { name: "Create customer" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/t/acme/customers/c-new"));
  });

  /** Deactivation only. There is no DELETE endpoint anywhere in this product. */
  it("offers no delete action", async () => {
    permissions = {
      "customer.view": ["ALL"],
      "customer.create": ["ALL"],
      "customer.deactivate": ["ALL"],
    };
    renderPage();
    await waitFor(() => expect(screen.getAllByText("Northwind Foods").length).toBeGreaterThan(0));
    expect(screen.queryByRole("button", { name: /delete/i })).toBeNull();
  });
});
