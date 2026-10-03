import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";

vi.mock("next/navigation", () => ({ useParams: () => ({ slug: "acme" }) }));
let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: { userType: "INTERNAL" } }) }));
vi.mock("@/components/ui/Toast", () => ({ useToast: () => ({ show: vi.fn() }) }));
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

const { default: Page } = await import("./page");
const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}
const mk = (id: string, state: "BREACHED" | "RUNNING" | "PAUSED") => ({
  caseId: id,
  caseName: `Case ${id}`,
  customerId: `cust-${id}`,
  customerName: `Customer ${id}`,
  clock: { clockId: id, state, elapsedDays: 3, targetDays: 2, pausedDays: 1, remainingDays: 1, dueToday: state === "RUNNING" },
});

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <Page />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  permissions = {};
  setTenantSlug("acme");
  vi.stubGlobal("fetch", fetchMock);
});
afterEach(() => {
  cleanup();
  fetchMock.mockReset();
  vi.unstubAllGlobals();
});

describe("SLA war room page", () => {
  it("renders the eyebrow, the strip with words, the three columns and the policy", async () => {
    fetchMock.mockResolvedValue(
      reply({
        calendarName: "Northwind calendar",
        summary: { breached: 2, dueToday: 1, clocksPaused: 3, autoEscalated: 4 },
        breached: [mk("a", "BREACHED")],
        dueToday: [mk("b", "RUNNING")],
        watch: [mk("c", "PAUSED")],
      }),
    );
    renderPage();
    expect(await screen.findByText("SLA WAR ROOM · BUSINESS DAYS · NORTHWIND CALENDAR")).toBeInTheDocument();
    const expected: [string, string, string][] = [
      ["breached", "BREACHED", "2"],
      ["dueToday", "DUE TODAY", "1"],
      ["clocksPaused", "CLOCKS PAUSED", "3"],
      ["autoEscalated", "AUTO-ESCALATED", "4"],
    ];
    for (const [key, word, n] of expected) {
      const cell = screen.getByTestId(`sla-summary-${key}`);
      expect(cell).toHaveTextContent(word);
      const value = within(cell).getByTestId("sla-summary-value");
      expect(value).toHaveTextContent(n);
      expect(value.style.font).toContain("var(--ob-font-family-data)");
    }
    for (const [name, caseName] of [["Breached", "Case a"], ["Due today", "Case b"], ["Watch", "Case c"]] as [string, string][]) {
      const col = screen.getByRole("heading", { name: new RegExp(`^${name}`) }).closest("section")!;
      expect(within(col).getByText(caseName)).toBeInTheDocument();
    }
    expect(screen.getByText("Escalation to the assignee's manager is automatic and cannot be opted out of.")).toBeInTheDocument();
    expect(screen.getByTestId("sla-columns").className).toContain("grid-cols-1 min-[900px]:grid-cols-3");
  });

  it("shows one empty state, with the calendar in the eyebrow, when nothing is listed", async () => {
    fetchMock.mockResolvedValue(
      reply({ calendarName: "Northwind calendar", summary: { breached: 0, dueToday: 0, clocksPaused: 0, autoEscalated: 0 }, breached: [], dueToday: [], watch: [] }),
    );
    renderPage();
    expect(await screen.findByText("Nothing is breached, due today or at risk.")).toBeInTheDocument();
    expect(screen.getByText(/NORTHWIND CALENDAR/)).toBeInTheDocument();
    expect(screen.queryByTestId("sla-columns")).toBeNull();
  });

  it("shows skeletons while loading", () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByLabelText("Loading")).toBeInTheDocument();
  });

  it("shows the error state on failure", async () => {
    fetchMock.mockResolvedValue(reply({ title: "boom" }, 500));
    renderPage();
    await waitFor(() => expect(screen.getByRole("alert")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  describe("card actions", () => {
    const card = (extra: Record<string, unknown> = {}) => ({ ...mk("a", "BREACHED"), stageName: "Verification", hasOpenRequests: true, ...extra });

    function board(cards: unknown[]) {
      fetchMock.mockImplementation(async (url: string) => {
        const u = String(url);
        if (u.endsWith("/sla/exceptions")) return reply({ summary: {}, breached: cards, dueToday: [], watch: [] });
        if (u.endsWith("/cases/a")) return reply({ id: "a", name: "Case a", ownerUserId: "u-1", attributes: {} });
        if (u.endsWith("/cases/a/tasks")) return reply([]);
        if (u.includes("/admin/users")) return reply({ content: [] });
        if (u.endsWith("/cases/a/document-requests")) return reply({ content: [] });
        if (u.endsWith("/cases/a/roadmap"))
          return reply({ stages: [{ id: "s", name: "Verification", milestones: [{ id: "m-9", name: "Collect KYC", status: "ACTIVE" }] }] });
        return reply({}, 404);
      });
    }
    const exceptionFetches = () => fetchMock.mock.calls.filter(([u]) => String(u).endsWith("/sla/exceptions")).length;

    it("Reassign opens the reassign dialog for that case", async () => {
      board([card()]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Reassign" }));
      expect(await screen.findByRole("dialog", { name: "Reassign" })).toBeInTheDocument();
      expect(await screen.findByLabelText("Case owner")).toBeInTheDocument();
    });

    it("Remind customer opens the remind dialog", async () => {
      board([card()]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Remind customer" }));
      expect(await screen.findByText("No open document requests on this case.")).toBeInTheDocument();
    });

    it("Force-complete goes straight to the reason dialog when a milestone escalation names the milestone", async () => {
      permissions = { "milestone.force_complete": ["ALL"] };
      board([card({ escalations: [{ subjectType: "TASK", subjectId: "t-1" }, { subjectType: "MILESTONE", subjectId: "m-7" }] })]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Force-complete" }));
      expect(await screen.findByLabelText(/reason/i)).toBeInTheDocument();
      fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: "why" } });
      fireEvent.click(screen.getByRole("button", { name: /request/i }));
      await waitFor(() =>
        expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith("/cases/a/milestones/m-7/force-complete"))).toBe(true),
      );
    });

    it("Force-complete opens the milestone picker when no milestone escalation exists, then the reason dialog", async () => {
      permissions = { "milestone.force_complete": ["ALL"] };
      board([card({ escalations: [{ subjectType: "TASK", subjectId: "t-1" }] })]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Force-complete" }));
      fireEvent.click(await screen.findByRole("button", { name: "Collect KYC" }));
      expect(await screen.findByLabelText(/reason/i)).toBeInTheDocument();
    });

    it("closing a dialog refetches the exceptions", async () => {
      board([card()]);
      renderPage();
      fireEvent.click(await screen.findByRole("button", { name: "Reassign" }));
      await screen.findByLabelText("Case owner");
      const before = exceptionFetches();
      fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
      await waitFor(() => expect(exceptionFetches()).toBeGreaterThan(before));
      expect(screen.queryByRole("dialog")).toBeNull();
    });
  });
});
