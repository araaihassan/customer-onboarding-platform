import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { TimelineTab } from "./TimelineTab";

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
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function renderTab() {
  return render(<TimelineTab caseId="c-1" />, { wrapper: makeWrapper() });
}

describe("TimelineTab", () => {
  it("renders the immutable list with mono timestamps and an event count", async () => {
    fetchMock.mockResolvedValue(
      reply({
        content: [
          { id: "e-1", occurredAt: "2026-08-16T09:41:00Z", action: "case.hold", summary: "Case placed on hold" },
        ],
        totalElements: 1,
        totalPages: 1,
      }),
    );

    renderTab();

    await waitFor(() => expect(screen.getByText("Case placed on hold")).not.toBeNull());
    expect(screen.getByText("2026-08-16 09:41")).not.toBeNull();
    expect(screen.getByText(/1 events/)).not.toBeNull();
  });

  it("says 'Immutable' in the header, because the audit trail is the point", async () => {
    fetchMock.mockResolvedValue(reply({ content: [{ id: "e-1", summary: "Case opened" }], totalElements: 1, totalPages: 1 }));

    renderTab();

    await waitFor(() => expect(screen.getByText(/Immutable · 1 events/)).not.toBeNull());
  });

  it("renders the empty state for a case with no events yet", async () => {
    fetchMock.mockResolvedValue(reply({ content: [], totalElements: 0, totalPages: 0 }));

    renderTab();

    await waitFor(() => expect(screen.getByText("No activity yet")).not.toBeNull());
  });

  it("paginates rather than truncating silently", async () => {
    fetchMock.mockResolvedValue(
      reply({
        content: Array.from({ length: 25 }, (_, i) => ({ id: `e-${i}`, summary: `Event ${i}` })),
        totalElements: 60,
        totalPages: 3,
      }),
    );

    renderTab();

    await waitFor(() => expect(screen.getByRole("navigation", { name: "Case timeline pages" })).not.toBeNull());
    expect(screen.getByText(/60 events/)).not.toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await waitFor(() => expect(fetchMock).toHaveBeenLastCalledWith(
      expect.stringContaining("page=1"),
      expect.anything(),
    ));
  });

  it("computes the footer's earlier-events count correctly across pages", async () => {
    // Page 0: 25 events shown, 60 total → 35 earlier events (on this page only)
    fetchMock.mockResolvedValueOnce(
      reply({
        content: Array.from({ length: 25 }, (_, i) => ({ id: `e-${i}`, summary: `Event ${i}` })),
        totalElements: 60,
        totalPages: 3,
      }),
    );

    renderTab();

    await waitFor(() => expect(screen.getByText(/60 events/)).not.toBeNull());
    expect(screen.getByText(/35 earlier events/)).not.toBeNull();

    // Page 1: 25 events shown, 60 total → 10 earlier events (60 - 25 already seen - 25 on this page)
    fetchMock.mockResolvedValueOnce(
      reply({
        content: Array.from({ length: 25 }, (_, i) => ({ id: `e-${25 + i}`, summary: `Event ${25 + i}` })),
        totalElements: 60,
        totalPages: 3,
      }),
    );

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await waitFor(() => expect(screen.getByText(/10 earlier events/)).not.toBeNull());

    // Page 2 (last page): 10 events shown, 60 total → 0 earlier events
    fetchMock.mockResolvedValueOnce(
      reply({
        content: Array.from({ length: 10 }, (_, i) => ({ id: `e-${50 + i}`, summary: `Event ${50 + i}` })),
        totalElements: 60,
        totalPages: 3,
      }),
    );

    fireEvent.click(screen.getByRole("button", { name: /next/i }));
    await waitFor(() => expect(screen.getByText(/0 earlier events/)).not.toBeNull());
  });
});
