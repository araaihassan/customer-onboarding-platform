import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Document, DocumentVisibilitySummary } from "@/lib/api/documents";

vi.mock("next/navigation", () => ({ useParams: () => ({ slug: "acme" }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: DocumentsPage } = await import("./page");

const fetchMock = vi.fn();

const companyShared: Document = {
  id: "doc-1",
  name: "MSA.pdf",
  customerId: "cust-1",
  category: "AGREEMENT",
  visibilityTier: "COMPANY_SHARED",
  status: "ACTIVE",
  currentVersionNumber: 1,
};

const sensitive: Document = {
  id: "doc-2",
  name: "Tax filing.pdf",
  customerId: "cust-1",
  category: "TAX",
  visibilityTier: "SENSITIVE",
  status: "ACTIVE",
  currentVersionNumber: 1,
};

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
  return render(<DocumentsPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockImplementation(async (url: string) => {
    if (url.includes("/documents/visibility-summary")) {
      const summary: DocumentVisibilitySummary = url.includes("visibilityTier=SENSITIVE")
        ? { visible: 1, hidden: 3 }
        : { visible: 2, hidden: 0 };
      return jsonReply(summary);
    }
    if (url.includes("/documents?")) {
      const content = url.includes("visibilityTier=SENSITIVE") ? [sensitive] : [companyShared, sensitive];
      return jsonReply({ content, totalPages: 1, totalElements: content.length });
    }
    return jsonReply({});
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("DocumentsPage", () => {
  it("renders the tenant-wide document list", async () => {
    renderPage();
    const table = await screen.findByRole("table");
    expect(within(table).getByText("MSA.pdf")).toBeInTheDocument();
    expect(within(table).getByText("Tax filing.pdf")).toBeInTheDocument();
  });

  it("shows the hidden-count line reflecting the unfiltered summary", async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText("02 VISIBLE · 00 HIDDEN BY SCOPE")).toBeInTheDocument());
  });

  /**
   * Selecting a scope filter must change what both `useDocuments` and
   * `useDocumentVisibilitySummary` fetch -- the wiring this task's own
   * pre-dispatch research named as the thing to prove, not a re-test of
   * either hook's own already-covered behaviour.
   */
  it("re-fetches the table and the hidden-count line for the selected tier", async () => {
    renderPage();
    await screen.findByRole("table");

    fireEvent.click(screen.getByRole("button", { name: "Sensitive" }));

    await waitFor(() => expect(within(screen.getByRole("table")).queryByText("MSA.pdf")).toBeNull());
    expect(within(screen.getByRole("table")).getByText("Tax filing.pdf")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("01 VISIBLE · 03 HIDDEN BY SCOPE")).toBeInTheDocument());

    const sensitiveCalls = fetchMock.mock.calls.filter((call) =>
      String(call[0]).includes("/documents?") && String(call[0]).includes("visibilityTier=SENSITIVE"),
    );
    expect(sensitiveCalls.length).toBeGreaterThan(0);
  });

  it("always renders the visibility explainer aside, per SCREENS.md §7", async () => {
    renderPage();
    await screen.findByRole("table");
    expect(screen.getByText("How visibility works")).toBeInTheDocument();
  });

  /**
   * This screen deliberately has no Upload action -- see the page's own doc
   * comment for the reasoning (no case-picker exists anywhere to drive
   * `UploadDialog`'s required `caseId`, and `SCREENS.md` §7 does not name one
   * anyway). Pinned here so a future change adding one does so deliberately.
   */
  it("has no Upload action", async () => {
    renderPage();
    await screen.findByRole("table");
    expect(screen.queryByRole("button", { name: /upload/i })).toBeNull();
  });
});
