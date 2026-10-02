import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { AgreementsTab } from "./AgreementsTab";

const replace = vi.fn();
let search = "";
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  usePathname: () => "/t/acme/customers/cust-1/cases/c-1",
  useSearchParams: () => new URLSearchParams(search),
}));

vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions: { "agreement.manage": ["ALL"] } }) }));

afterEach(cleanup);

const fetchMock = vi.fn();
const NOW = new Date("2026-09-01T10:00:00Z");

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return render(<AgreementsTab caseId="c-1" now={NOW} />, { wrapper: Wrapper });
}

const signed = {
  id: "a-1",
  name: "Master Services Agreement",
  recordMode: "FILE_BACKED",
  status: "SIGNED",
  displayStatus: "SIGNED",
  latestVersionNumber: 3,
  signedAt: "2026-08-14T09:00:00Z",
  expiresAt: "2027-08-14",
};

beforeEach(() => {
  replace.mockReset();
  search = "";
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("AgreementsTab", () => {
  it("renders one row per live agreement with its name, meta line and status chip", async () => {
    fetchMock.mockResolvedValue(
      reply([signed, { id: "a-2", name: "Data Processing Addendum", recordMode: "FILE_BACKED", status: "DRAFT", displayStatus: "DRAFT", latestVersionNumber: 0 }]),
    );
    renderTab();
    await waitFor(() => expect(screen.getByText("Master Services Agreement")).not.toBeNull());
    expect(screen.getByText("Data Processing Addendum")).not.toBeNull();
    expect(screen.getByText("Signed")).not.toBeNull();
    expect(screen.getByText("Draft")).not.toBeNull();
    expect(screen.getAllByRole("button", { name: "Open" })).toHaveLength(2);
  });

  it("the meta line states version, record mode and signing", async () => {
    fetchMock.mockResolvedValue(reply([signed]));
    renderTab();
    await waitFor(() => expect(screen.getByTestId("agreement-meta").textContent).toBe("v3 · file-backed · signed 14 Aug 2026 via manual record"));
  });

  it("a structured-only agreement's meta says no file is required", async () => {
    fetchMock.mockResolvedValue(
      reply([{ id: "a-3", name: "Side letter", recordMode: "STRUCTURED_ONLY", status: "DRAFT", displayStatus: "DRAFT", latestVersionNumber: 0 }]),
    );
    renderTab();
    await waitFor(() => expect(screen.getByTestId("agreement-meta").textContent).toBe("structured record only · no file required"));
  });

  it("collapses cancelled agreements under 'Replaced (n)'", async () => {
    fetchMock.mockResolvedValue(
      reply([
        { id: "a-4", name: "Old draft", recordMode: "FILE_BACKED", status: "CANCELLED", displayStatus: "CANCELLED" },
        { id: "a-5", name: "Successor draft", recordMode: "FILE_BACKED", status: "DRAFT", displayStatus: "DRAFT" },
      ]),
    );
    const { container } = renderTab();
    await waitFor(() => expect(screen.getByText("Replaced (1)")).not.toBeNull());
    const details = container.querySelector("details")!;
    expect(details.open).toBe(false);
    expect(within(details).getByText("Old draft")).not.toBeNull();
    // the successor is a live row, outside the collapsed group
    expect(details.textContent).not.toContain("Successor draft");
    expect(screen.getByText("Successor draft")).not.toBeNull();
  });

  it("shows 'Expires in 12d' when a signed agreement expires within 30 days", async () => {
    fetchMock.mockResolvedValue(reply([{ ...signed, expiresAt: "2026-09-13" }, { ...signed, id: "a-9", name: "Far", expiresAt: "2026-12-31" }]));
    renderTab();
    await waitFor(() => expect(screen.getAllByText(/Expires in/)).toHaveLength(1));
    expect(screen.getByText(/Expires in 12d/)).not.toBeNull();
  });

  it("shows the EXPIRED chip for a derived-expired agreement", async () => {
    fetchMock.mockResolvedValue(reply([{ ...signed, status: "SIGNED", displayStatus: "EXPIRED", expiresAt: "2026-08-20" }]));
    renderTab();
    await waitFor(() => expect(screen.getByText("Expired")).not.toBeNull());
    expect(screen.queryByText("Signed")).toBeNull();
    expect(screen.queryByText(/Expires in/)).toBeNull();
  });

  it("renders the empty state when the case has no agreements", async () => {
    fetchMock.mockResolvedValue(reply([]));
    renderTab();
    await waitFor(() => expect(screen.getByText("No agreements on this journey")).not.toBeNull());
    expect(screen.getByText(/SIGNATURE requirement in the workflow creates one automatically/)).not.toBeNull();
  });

  it("renders a skeleton while loading and an error state on failure", async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const first = renderTab();
    expect(first.container.querySelector("[aria-busy='true']")).not.toBeNull();
    first.unmount();
    cleanup();

    fetchMock.mockReset();
    fetchMock.mockResolvedValue(reply({ message: "boom" }, 500));
    renderTab();
    await waitFor(() => expect(screen.getByRole("button", { name: /try again/i })).not.toBeNull());
  });

  it("Open sets ?agreement={id} and the detail panel appears when the param is present", async () => {
    fetchMock.mockResolvedValue(reply([signed]));
    const first = renderTab();
    await waitFor(() => expect(screen.getByText("Master Services Agreement")).not.toBeNull());
    expect(screen.queryByRole("region", { name: "Agreement detail" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Open" }));
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("agreement=a-1"));
    expect(replace).toHaveBeenCalledWith(expect.stringContaining("tab=agreements"));
    first.unmount();
    cleanup();

    search = "tab=agreements&agreement=a-1";
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(url.endsWith("/agreements/a-1") ? reply({ agreement: signed, signatories: [], versions: [], signatures: [] }) : reply([signed])),
    );
    renderTab();
    const panel = await screen.findByRole("region", { name: "Agreement detail" });
    expect(panel.getAttribute("data-agreement-id")).toBe("a-1");
    await waitFor(() => expect(within(panel).getByRole("heading", { name: "Master Services Agreement" })).not.toBeNull());
  });

  it("closing the detail panel removes ?agreement= from the URL", async () => {
    search = "tab=agreements&agreement=a-1";
    fetchMock.mockImplementation((url: string) =>
      Promise.resolve(url.endsWith("/agreements/a-1") ? reply({ agreement: signed, signatories: [], versions: [], signatures: [] }) : reply([signed])),
    );
    renderTab();
    fireEvent.click(await screen.findByRole("button", { name: "Close agreement detail" }));
    const target = replace.mock.calls[0]![0] as string;
    expect(target).toContain("tab=agreements");
    expect(target).not.toContain("agreement=a-1");
  });
});
