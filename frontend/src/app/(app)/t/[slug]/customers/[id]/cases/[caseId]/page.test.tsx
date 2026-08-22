import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Case } from "@/lib/api/cases";
import type { Customer } from "@/lib/api/customers";

let searchParams = new URLSearchParams();
const replace = vi.fn((url: string) => {
  searchParams = new URLSearchParams(url.split("?")[1] ?? "");
});

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme", id: "cust-1", caseId: "case-1" }),
  usePathname: () => "/t/acme/customers/cust-1/cases/case-1",
  useRouter: () => ({ push: vi.fn(), replace }),
  useSearchParams: () => searchParams,
}));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: CaseWorkspacePage } = await import("./page");

const fetchMock = vi.fn();

const customer: Customer = { id: "cust-1", displayName: "Northwind Foods", status: "ACTIVE" };
const caseData: Case = {
  id: "case-1",
  customerId: "cust-1",
  versionNo: 2,
  status: "ACTIVE",
  currentStageName: "Registration",
  progressPercent: 20,
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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<CaseWorkspacePage />, { wrapper: Wrapper });
}

beforeEach(() => {
  searchParams = new URLSearchParams();
  replace.mockClear();
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.endsWith("/roadmap")) return jsonReply({ stages: [] });
    if (url.endsWith("/cases")) return jsonReply([caseData]);
    if (url.endsWith("/cases/case-1")) return jsonReply(caseData);
    if (url.endsWith("/customers/cust-1")) return jsonReply(customer);
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("CaseWorkspacePage", () => {
  it("defaults to the journey tab", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("tab", { name: "Journey", selected: true })).not.toBeNull());
  });

  /** So a reload, back button, or a shared link all land where the reader was. */
  it("renders the tab from the query param so a reload lands where the reader was", async () => {
    searchParams = new URLSearchParams("tab=timeline");
    renderPage();

    await waitFor(() => expect(screen.getByRole("tab", { name: "Timeline", selected: true })).not.toBeNull());
    expect(screen.getByText("The timeline isn't available here yet.")).not.toBeNull();
  });

  it("carries the new tab into the URL when the reader switches", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByRole("tab", { name: "Journey" })).not.toBeNull());

    screen.getByRole("tab", { name: "Tasks" }).click();

    expect(replace).toHaveBeenCalledWith("/t/acme/customers/cust-1/cases/case-1?tab=tasks");
  });

  it("shows the case's own header once loaded", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("Northwind Foods")).not.toBeNull());
    // Appears twice -- the header's Stage fact and the switcher's active chip.
    expect(screen.getAllByText("Registration").length).toBeGreaterThan(0);
  });
});
