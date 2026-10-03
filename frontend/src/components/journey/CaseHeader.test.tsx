import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render as rtlRender, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import { CaseHeader } from "./CaseHeader";
import type { Case } from "@/lib/api/cases";
import type { Customer } from "@/lib/api/customers";

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

beforeEach(() => {
  fetchMock.mockReset();
  // Default: the stage has no clock.
  fetchMock.mockImplementation(async () => reply({}, 404));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

function render(ui: ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return rtlRender(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

const caseData: Case = {
  id: "01a0000e-0000-7000-8000-000000000001",
  customerId: "cust-1",
  versionNo: 4,
  status: "ACTIVE",
  currentStageName: "Legal Review",
  progressPercent: 42,
  targetCompletionDate: "2026-09-30",
  startedAt: "2026-08-01T00:00:00Z",
  totalHoldDays: 0,
};

const customer: Customer = {
  id: "cust-1",
  displayName: "Acme Corp",
  legalName: "Acme Corporation Ltd",
  status: "ACTIVE",
};

describe("CaseHeader", () => {
  it("composes case facts with the customer name from the existing customer query", () => {
    render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
    expect(screen.getByText("Acme Corp")).not.toBeNull();
    expect(screen.getByText("Legal Review")).not.toBeNull();
  });

  it("renders machine values in mono and human text in Archivo", () => {
    render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);

    const stageValue = screen.getByText("Legal Review");
    expect(stageValue.style.fontFamily || stageValue.style.font).not.toContain("var(--ob-font-family-data)");

    const startedValue = screen.getByText("2026-08-01");
    expect(startedValue.style.font).toContain("var(--ob-font-family-data)");
  });

  it("shows the frozen version as 'workflow v4 (frozen)'", () => {
    render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
    expect(screen.getByText(/workflow v4 \(frozen\)/)).not.toBeNull();
  });

  it("reflows the five fact columns to two rows below 1280px", () => {
    render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
    const grid = screen.getByTestId("case-fact-grid");
    expect(grid.className).toContain("grid-cols-3");
    expect(grid.className).toContain("xl:grid-cols-5");
  });

  /**
   * Task 34, Ruling 2: the primary `Request document` action from
   * `SCREENS.md` §3 -- no `Message customer` button, deliberately (there is
   * no messaging feature anywhere in this sub-project's scope).
   */
  it("offers a primary Request document action that calls onRequestDocument", () => {
    const onRequestDocument = vi.fn();
    render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={onRequestDocument} />);

    expect(screen.queryByRole("button", { name: "Message customer" })).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Request document" }));
    expect(onRequestDocument).toHaveBeenCalled();
  });

  describe("SLA chip", () => {
    it("shows 'SLA PAUSED 3.1d' next to the status pill when the stage has a paused clock", async () => {
      fetchMock.mockImplementation(async () => reply({ state: "PAUSED", pausedDays: 3.14 }));
      render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
      expect(await screen.findByText("SLA PAUSED 3.1d")).not.toBeNull();
      expect(fetchMock.mock.calls[0]![0]).toBe(`/api/t/acme/cases/${caseData.id}/sla-clock`);
    });

    it("renders no chip when the clock is null (404)", async () => {
      render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
      await waitFor(() => expect(fetchMock).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 0));
      expect(screen.queryByTestId("sla-chip")).toBeNull();
    });

    it("renders no chip while the clock is loading", () => {
      fetchMock.mockImplementation(() => new Promise(() => {}));
      render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
      expect(screen.queryByTestId("sla-chip")).toBeNull();
      expect(screen.getByText("Acme Corp")).not.toBeNull();
    });

    it.each([403, 500])("treats a %i as no clock: no chip, header intact", async (status) => {
      fetchMock.mockImplementation(async () => reply({ title: "x" }, status));
      render(<CaseHeader caseData={caseData} customer={customer} onRequestDocument={vi.fn()} />);
      await waitFor(() => expect(fetchMock).toHaveBeenCalled());
      await new Promise((r) => setTimeout(r, 20));
      expect(screen.queryByTestId("sla-chip")).toBeNull();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.getByText("Acme Corp")).not.toBeNull();
      expect(screen.getByRole("button", { name: "Request document" })).not.toBeNull();
    });
  });
});
