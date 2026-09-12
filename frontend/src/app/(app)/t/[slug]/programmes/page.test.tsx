import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Customer } from "@/lib/api/customers";
import type { Programme } from "@/lib/api/programmes";

let permissions: Record<string, string[]> = {};
let searchParam = "";

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme" }),
  useSearchParams: () => new URLSearchParams(searchParam ? `customer=${searchParam}` : ""),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: ProgrammesPage } = await import("./page");

const fetchMock = vi.fn();

const customer: Customer = {
  id: "cust-1",
  displayName: "Northwind Foods",
  status: "ACTIVE",
};

const programmes: Programme[] = [
  { id: "prog-1", name: "Northwind rollout", customerId: "cust-1", status: "ACTIVE" },
];

function jsonReply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body ?? {}),
    json: async () => body,
  } as unknown as Response;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ProgrammesPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = { "programme.view": ["ALL"], "customer.view": ["ALL"] };
  searchParam = "";
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/customers?")) return jsonReply({ content: [customer], totalPages: 1, totalElements: 1 });
    if (url.endsWith("/customers/cust-1")) return jsonReply(customer);
    if (url.endsWith("/customers/cust-1/programmes")) return jsonReply(programmes);
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("ProgrammesPage", () => {
  it("shows the no-access state without programme.view", () => {
    permissions = {};
    renderPage();
    expect(screen.getByText("Not available to you")).toBeInTheDocument();
  });

  it("starts as a customer picker with no results until a search is typed", () => {
    renderPage();
    expect(screen.getByPlaceholderText("Search customers")).toBeInTheDocument();
    expect(screen.queryByText("Northwind Foods")).toBeNull();
  });

  it("finds a customer by search and links into their programmes", async () => {
    renderPage();
    fireEvent.change(screen.getByPlaceholderText("Search customers"), { target: { value: "North" } });

    const link = await screen.findByRole("link", { name: /northwind foods/i });
    expect(link).toHaveAttribute("href", "/t/acme/programmes?customer=cust-1");
  });

  it("shows the picked customer's programmes, once a customer is in the URL", async () => {
    searchParam = "cust-1";
    renderPage();

    await waitFor(() => expect(screen.getByText("Northwind rollout")).toBeInTheDocument());
    expect(screen.getByRole("link", { name: "Northwind rollout" })).toHaveAttribute(
      "href",
      "/t/acme/programmes/prog-1",
    );
    expect(screen.getByRole("link", { name: "Change customer" })).toHaveAttribute("href", "/t/acme/programmes");
  });

  it("shows the empty state when the picked customer has no programmes yet", async () => {
    searchParam = "cust-1";
    fetchMock.mockImplementation(async (url: string) => {
      if (url.endsWith("/customers/cust-1")) return jsonReply(customer);
      if (url.endsWith("/customers/cust-1/programmes")) return jsonReply([]);
      return jsonReply({});
    });
    renderPage();

    await waitFor(() => expect(screen.getByText("No programmes yet")).toBeInTheDocument());
  });
});
