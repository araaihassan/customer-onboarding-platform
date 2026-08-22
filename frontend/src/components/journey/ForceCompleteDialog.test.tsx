import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { ForceCompleteDialog } from "./ForceCompleteDialog";

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

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "a-1", status: "PENDING" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("ForceCompleteDialog", () => {
  it("requires a reason to force-complete", () => {
    render(<ForceCompleteDialog caseId="c-1" milestoneId="m-1" onClose={vi.fn()} />, { wrapper: makeWrapper() });

    fireEvent.click(screen.getByRole("button", { name: /request/i }));

    expect(fetchMock).not.toHaveBeenCalled();
    expect(screen.getByText(/required/i)).not.toBeNull();
  });

  it("submits the trimmed reason and closes on success", async () => {
    const onClose = vi.fn();
    render(<ForceCompleteDialog caseId="c-1" milestoneId="m-1" onClose={onClose} />, { wrapper: makeWrapper() });

    fireEvent.change(screen.getByLabelText(/reason/i), { target: { value: "  Customer confirmed by phone  " } });
    fireEvent.click(screen.getByRole("button", { name: /request/i }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    const body = JSON.parse((fetchMock.mock.calls.at(-1)![1] as RequestInit).body as string);
    expect(body).toEqual({ reason: "Customer confirmed by phone" });
  });
});
