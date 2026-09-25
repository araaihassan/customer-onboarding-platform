import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { t } from "@/lib/i18n";
import { RequestDocumentDialog } from "./RequestDocumentDialog";

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

function renderDialog(onClose = vi.fn()) {
  return { onClose, ...render(<RequestDocumentDialog caseId="c-1" onClose={onClose} />, { wrapper: makeWrapper() }) };
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "req-1" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("RequestDocumentDialog", () => {
  /**
   * The brief's own Step 1 test target: "the request dialog exposes
   * `requires_review`" -- a real control exists, and its value (not a
   * hardcoded `false`) reaches the submitted request body.
   */
  it("sends requiresReview:true when the requires-review control is checked", async () => {
    renderDialog();

    fireEvent.click(screen.getByLabelText(t("documents.request.requiresReview")));
    fireEvent.click(screen.getByRole("button", { name: t("documents.request.submit") }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/cases/c-1/document-requests");
    const body = JSON.parse(init.body as string);
    expect(body.requiresReview).toBe(true);
  });

  it("sends requiresReview:false when left unchecked, never omitting the field silently", async () => {
    renderDialog();

    fireEvent.click(screen.getByRole("button", { name: t("documents.request.submit") }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body.requiresReview).toBe(false);
  });

  it("submits the selected category", async () => {
    renderDialog();

    fireEvent.change(screen.getByLabelText(t("documents.request.category")), { target: { value: "TAX" } });
    fireEvent.click(screen.getByRole("button", { name: t("documents.request.submit") }));

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string);
    expect(body.category).toBe("TAX");
  });

  it("closes on a successful submission", async () => {
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: t("documents.request.submit") }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
