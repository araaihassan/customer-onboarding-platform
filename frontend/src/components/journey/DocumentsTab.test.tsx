import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { DocumentsTab } from "./DocumentsTab";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

// `vi.mock` factories are hoisted above every other statement in the file,
// including a plain `const` -- `vi.hoisted` is what makes a value the
// factory closes over survive that hoist, so the mock and the test body
// below share the exact same `vi.fn()` instance.
const { downloadDocumentVersion } = vi.hoisted(() => ({ downloadDocumentVersion: vi.fn() }));
vi.mock("@/lib/api/documents", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/documents")>();
  return { ...actual, downloadDocumentVersion };
});

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

const sharedDoc = {
  id: "doc-1",
  caseId: "c-1",
  customerId: "cust-1",
  name: "Master Services Agreement",
  category: "CONTRACT",
  visibilityTier: "COMPANY_SHARED",
  status: "ACTIVE",
  currentVersionId: "v-1",
  currentVersionNumber: 2,
};

const sensitiveDoc = {
  id: "doc-2",
  caseId: "c-1",
  customerId: "cust-1",
  name: "Tax certificate",
  category: "TAX",
  visibilityTier: "SENSITIVE",
  status: "ACTIVE",
  currentVersionId: "v-2",
  currentVersionNumber: 1,
};

function mockDocuments(content: unknown[]) {
  fetchMock.mockImplementation(() => Promise.resolve(reply({ content, totalElements: content.length })));
}

beforeEach(() => {
  permissions = { "document.upload": ["ALL"] };
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  downloadDocumentVersion.mockReset();
});

function renderTab() {
  return render(<DocumentsTab caseId="c-1" />, { wrapper: makeWrapper() });
}

describe("DocumentsTab", () => {
  it("renders an empty state with no documents", async () => {
    mockDocuments([]);
    renderTab();
    await waitFor(() => expect(screen.getByText(/no documents yet/i)).not.toBeNull());
  });

  it("renders a row per SCREENS.md §3's shape: name, meta line (visibility + version), a Chip, and an Open button", async () => {
    mockDocuments([sharedDoc]);
    renderTab();

    await waitFor(() => expect(screen.getByText("Master Services Agreement")).not.toBeNull());
    expect(screen.getByText(/company shared/i)).not.toBeNull();
    expect(screen.getByText(/v2/)).not.toBeNull();
    expect(screen.getByText(/active/i)).not.toBeNull();
    expect(screen.getByRole("button", { name: /open/i })).not.toBeNull();
  });

  it("shows a lock glyph before a SENSITIVE document's name, same convention as the tenant-wide table", async () => {
    mockDocuments([sensitiveDoc]);
    renderTab();

    await waitFor(() => expect(screen.getByText("Tax certificate")).not.toBeNull());
    // The lock icon renders as an inline svg beside the name -- assert via
    // the row containing an svg, the same shape DocumentTable's own test
    // suite already uses for this exact glyph.
    const row = screen.getByText("Tax certificate").closest("div");
    expect(row?.querySelector("svg")).not.toBeNull();
  });

  it("downloads the document's current version by NUMBER when Open is clicked", async () => {
    mockDocuments([sharedDoc]);
    renderTab();

    await waitFor(() => expect(screen.getByRole("button", { name: /open/i })).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: /open/i }));

    await waitFor(() =>
      expect(downloadDocumentVersion).toHaveBeenCalledWith("doc-1", 2, "Master Services Agreement"),
    );
  });

  it("disables Open when the document has no current version number", async () => {
    mockDocuments([{ ...sharedDoc, currentVersionId: undefined, currentVersionNumber: undefined }]);
    renderTab();

    await waitFor(() => expect(screen.getByRole("button", { name: /open/i })).not.toBeNull());
    expect((screen.getByRole("button", { name: /open/i }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("hides the upload action without document.upload", async () => {
    permissions = {};
    mockDocuments([sharedDoc]);
    renderTab();

    await waitFor(() => expect(screen.getByText("Master Services Agreement")).not.toBeNull());
    expect(screen.queryByRole("button", { name: /upload document/i })).toBeNull();
  });

  it("opens the upload dialog from the empty state's own action", async () => {
    mockDocuments([]);
    renderTab();

    await waitFor(() => expect(screen.getByRole("button", { name: /upload document/i })).not.toBeNull());
    fireEvent.click(screen.getByRole("button", { name: /upload document/i }));

    expect(screen.getByRole("dialog")).not.toBeNull();
  });
});
