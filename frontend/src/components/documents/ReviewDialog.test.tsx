import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { t } from "@/lib/i18n";
import { ReviewAffordance, ReviewDialog } from "./ReviewDialog";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

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
  return {
    onClose,
    ...render(<ReviewDialog documentId="doc-1" versionNo={2} onClose={onClose} />, { wrapper: makeWrapper() }),
  };
}

beforeEach(() => {
  permissions = { "document.review": ["ALL"] };
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "doc-1", versionNo: 2, decision: "APPROVED" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("ReviewDialog", () => {
  /** The brief's own Step 1 test target: rejecting requires a note. */
  it("disables submit when rejecting with an empty note", () => {
    renderDialog();

    fireEvent.click(screen.getByLabelText(t("documents.review.decision.reject")));

    const submit = screen.getByRole("button", { name: t("documents.review.submit") }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
  });

  it("enables submit once a note is entered for a rejection", () => {
    renderDialog();

    fireEvent.click(screen.getByLabelText(t("documents.review.decision.reject")));
    fireEvent.change(screen.getByLabelText(t("documents.review.note")), { target: { value: "Missing signature page" } });

    const submit = screen.getByRole("button", { name: t("documents.review.submit") }) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);
  });

  /** ReviewVersionRequest.note is deliberately NOT @NotBlank for an approval -- a quick "approved, no comment" click is legitimate. */
  it("never requires a note to approve", async () => {
    renderDialog();

    const submit = screen.getByRole("button", { name: t("documents.review.submit") }) as HTMLButtonElement;
    expect(submit.disabled).toBe(false);

    fireEvent.click(submit);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toContain("/documents/doc-1/versions/2/review");
    const body = JSON.parse(init.body as string);
    expect(body.decision).toBe("APPROVED");
  });

  it("closes on a successful review", async () => {
    const { onClose } = renderDialog();

    fireEvent.click(screen.getByRole("button", { name: t("documents.review.submit") }));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});

describe("ReviewAffordance", () => {
  /** The brief's own Step 1 test target: no document.review, no affordance at all. */
  it("renders nothing for a caller without document.review", () => {
    permissions = {};
    render(<ReviewAffordance documentId="doc-1" versionNo={2} />, { wrapper: makeWrapper() });

    expect(screen.queryByRole("button")).toBeNull();
  });

  it("offers the review action to a holder of document.review, opening the dialog", () => {
    render(<ReviewAffordance documentId="doc-1" versionNo={2} />, { wrapper: makeWrapper() });

    const trigger = screen.getByRole("button", { name: t("documents.review.action") });
    fireEvent.click(trigger);

    expect(screen.getByRole("dialog")).not.toBeNull();
  });
});
