import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Agreement } from "@/lib/api/agreements";

let permissions: Record<string, string[]> = {};
vi.mock("next/navigation", () => ({ useParams: () => ({ slug: "acme" }) }));
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: { userType: "INTERNAL" } }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: AgreementsPage } = await import("./page");

const fetchMock = vi.fn();

const msa: Agreement = {
  id: "ag-1",
  caseId: "case-1",
  customerId: "cust-1",
  customerName: "Acme Corp",
  name: "Master services agreement",
  recordMode: "FILE_BACKED",
  displayStatus: "SIGNED",
  latestVersionNumber: 2,
  ownerUserId: "u-1",
};
const dpa: Agreement = { ...msa, id: "ag-2", name: "Data processing addendum", displayStatus: "DRAFT" };

function jsonReply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<AgreementsPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  permissions = { "agreement.view": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/agreements/summary")) {
      return jsonReply({ draft: 6, underReview: 9, sent: 4, awaitingSignature: 7, signed: 52, expiringWithin30Days: 3 });
    }
    if (url.includes("/agreements?")) {
      const content = url.includes("status=DRAFT") ? [dpa] : [msa, dpa];
      return jsonReply({ content, totalPages: 1, totalElements: content.length });
    }
    if (url.includes("/admin/users")) {
      return jsonReply({ content: [{ id: "u-1", fullName: "Dana Owner" }], totalPages: 1, totalElements: 1 });
    }
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

const grid = (c: HTMLElement) => c.querySelector("[data-view='table']") as HTMLElement;

describe("AgreementsPage", () => {
  it("renders the lifecycle card and the table", async () => {
    const { container } = renderPage();
    expect(await screen.findByText("AGREEMENT LIFECYCLE · MANUAL SIGNING")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("lifecycle-signed")).toHaveTextContent("52"));
    await waitFor(() => expect(within(grid(container)).getByText("Master services agreement")).toBeInTheDocument());
  });

  it("filtering by status refetches with ?status=", async () => {
    const { container } = renderPage();
    await waitFor(() => expect(within(grid(container)).getByText("Master services agreement")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Draft" }));
    await waitFor(() => expect(within(grid(container)).queryByText("Master services agreement")).toBeNull());
    const calls = fetchMock.mock.calls.filter(
      (c) => String(c[0]).includes("/agreements?") && String(c[0]).includes("status="),
    );
    expect(calls.length).toBeGreaterThan(0);
    expect(String(calls[0]?.[0])).toContain("status=DRAFT");
  });

  it("resolves owner names when the viewer holds user.view", async () => {
    permissions = { "agreement.view": ["ALL"], "user.view": ["ALL"] };
    const { container } = renderPage();
    await waitFor(() => expect(within(grid(container)).getAllByText("Dana Owner").length).toBeGreaterThan(0));
  });

  it("does not request users or show an owner without user.view", async () => {
    const { container } = renderPage();
    await waitFor(() => expect(within(grid(container)).getByText("Master services agreement")).toBeInTheDocument());
    expect(fetchMock.mock.calls.some((c) => String(c[0]).includes("/admin/users"))).toBe(false);
    expect(screen.queryByText("Dana Owner")).toBeNull();
    expect(grid(container).textContent).not.toContain("u-1");
  });

  it("renders the error state with a retry", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.includes("/agreements?") ? jsonReply({ title: "boom" }, 500) : jsonReply({ draft: 0 }),
    );
    renderPage();
    expect(await screen.findByText("Something went wrong")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("renders the empty state", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url.includes("/agreements?") ? jsonReply({ content: [], totalPages: 0, totalElements: 0 }) : jsonReply({}),
    );
    renderPage();
    expect(await screen.findByText("No agreements")).toBeInTheDocument();
  });

  it("renders a loading skeleton before data arrives", () => {
    fetchMock.mockImplementation(() => new Promise(() => {}));
    const { container } = renderPage();
    expect(container.querySelector("[data-view='table']")).toBeNull();
    expect(container.querySelector("[aria-busy='true']")).not.toBeNull();
  });
});
