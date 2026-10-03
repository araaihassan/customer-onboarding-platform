import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";

const { RemindCustomerDialog } = await import("./RemindCustomerDialog");
const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body ?? {}), json: async () => body } as unknown as Response;
}

const base = { caseId: "c-1", status: "OPEN", requestedOfContactId: "ct-1" };
const requests = {
  content: [
    { ...base, id: "r-1", category: "KYC", description: "Passport copy", remindersSent: 2, lastRemindedAt: "2026-10-03T09:00:00Z" },
    { ...base, id: "r-2", category: "TAX", description: "Tax certificate", remindersSent: 0 },
    { ...base, id: "r-3", category: "OTHER", description: "Mystery", requestedOfContactId: undefined },
    { ...base, id: "r-4", category: "NDA", description: "Fulfilled one", status: "FULFILLED" },
  ],
};

let remindReply: () => Response;
function renderDialog(onClose = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const spy = vi.spyOn(client, "invalidateQueries");
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  render(<RemindCustomerDialog caseId="c-1" onClose={onClose} />, { wrapper });
  return { onClose, spy };
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
  remindReply = () => reply({ ...requests.content[1], remindersSent: 1 });
  fetchMock.mockImplementation(async (url: string, init?: RequestInit) => {
    if (init?.method === "POST") return remindReply();
    if (String(url).endsWith("/cases/c-1/document-requests")) return reply(requests);
    return reply({}, 404);
  });
});
afterEach(cleanup);

const row = (name: RegExp | string) => screen.getByText(name).closest("li") as HTMLElement;

describe("RemindCustomerDialog", () => {
  it("lists only OPEN requests with category, reminder history in the data font, and a Remind button", async () => {
    renderDialog();
    await screen.findByText("Passport copy");
    expect(screen.queryByText("Fulfilled one")).toBeNull();
    const first = row("Passport copy");
    expect(within(first).getByText("KYC")).toBeInTheDocument();
    const history = within(first).getByText("Reminded 2× · last 3 Oct");
    expect(history.style.fontFamily).toContain("var(--ob-font-family-data)");
    expect(within(row("Tax certificate")).getByText("Not reminded yet")).toBeInTheDocument();
    expect(within(first).getByRole("button", { name: "Remind" })).toBeEnabled();
  });

  it("reminds and refetches the request list and the exceptions", async () => {
    const { spy } = renderDialog();
    await screen.findByText("Tax certificate");
    fireEvent.click(within(row("Tax certificate")).getByRole("button", { name: "Remind" }));
    await waitFor(() =>
      expect(
        fetchMock.mock.calls.some(([u, i]) => String(u).endsWith("/document-requests/r-2/remind") && (i as RequestInit).method === "POST"),
      ).toBe(true),
    );
    await waitFor(() =>
      expect(spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey))).toContain(
        JSON.stringify(["sla", "exceptions"]),
      ),
    );
  });

  it("a 409 shows the 24-hour message on that row and keeps the dialog open", async () => {
    remindReply = () => reply({ status: 409, detail: "x" }, 409);
    const { onClose } = renderDialog();
    await screen.findByText("Tax certificate");
    fireEvent.click(within(row("Tax certificate")).getByRole("button", { name: "Remind" }));
    expect(await within(row("Tax certificate")).findByText("Already reminded in the last 24 hours.")).toBeInTheDocument();
    expect(within(row("Passport copy")).queryByText("Already reminded in the last 24 hours.")).toBeNull();
    expect(onClose).not.toHaveBeenCalled();
  });

  it("a 422 shows the server's detail", async () => {
    remindReply = () => reply({ status: 422, detail: "The contact has no email address" }, 422);
    renderDialog();
    await screen.findByText("Tax certificate");
    fireEvent.click(within(row("Tax certificate")).getByRole("button", { name: "Remind" }));
    expect(await within(row("Tax certificate")).findByText("The contact has no email address")).toBeInTheDocument();
  });

  it("a request with no contact says so and cannot be reminded", async () => {
    renderDialog();
    await screen.findByText("Mystery");
    const r = row("Mystery");
    expect(within(r).getByText("No contact on this request")).toBeInTheDocument();
    expect(within(r).getByRole("button", { name: "Remind" })).toBeDisabled();
  });

  it("shows an empty state when nothing is open", async () => {
    fetchMock.mockImplementation(async () => reply({ content: [] }));
    renderDialog();
    expect(await screen.findByText("No open document requests on this case.")).toBeInTheDocument();
  });

  it("shows an error state when the list cannot be loaded", async () => {
    fetchMock.mockImplementation(async () => reply({}, 500));
    renderDialog();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
