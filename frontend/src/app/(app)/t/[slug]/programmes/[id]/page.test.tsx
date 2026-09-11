import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { ProgrammeDetail } from "@/lib/api/programmes";

let permissions: Record<string, string[]> = {};

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));
vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme", id: "prog-1" }),
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: ProgrammeDetailPage } = await import("./page");

const fetchMock = vi.fn();

const detail: ProgrammeDetail = {
  programme: {
    id: "prog-1",
    name: "Northwind rollout",
    customerId: "cust-1",
    customerName: "Northwind Foods",
    status: "ACTIVE",
  },
  journeys: [],
  rolledUpProgressPercent: 0,
  journeysCovered: 0,
};

let status = 200;

function jsonReply(body: unknown, code = 200) {
  return {
    ok: code < 400,
    status: code,
    text: async () => JSON.stringify(body ?? {}),
    json: async () => body,
  } as unknown as Response;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<ProgrammeDetailPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = {};
  status = 200;
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/admin/users")) return jsonReply({ content: [] });
    if (status !== 200) return jsonReply({ message: "not found" }, status);
    return jsonReply(detail);
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("ProgrammeDetailPage", () => {
  it("shows the programme once it loads", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind rollout")).toBeInTheDocument());
  });

  it("renders Not found for a 404, and offers the way back", async () => {
    status = 404;
    renderPage();

    await waitFor(() => expect(screen.getByText("Not found")).toBeInTheDocument());
    expect(screen.getByRole("link", { name: /back to programmes/i })).toHaveAttribute(
      "href",
      "/t/acme/programmes",
    );
  });

  it("distinguishes a server error from a missing record", async () => {
    status = 500;
    renderPage();

    await waitFor(() => expect(screen.getByText("Something went wrong")).toBeInTheDocument());
    expect(screen.queryByText("Not found")).toBeNull();
  });
});
