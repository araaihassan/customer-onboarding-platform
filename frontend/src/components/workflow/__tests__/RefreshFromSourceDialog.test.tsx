import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/vitest";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { WorkflowTemplate } from "@/lib/api/workflows";
import { RefreshFromSourceDialog } from "../RefreshFromSourceDialog";

const push = vi.fn();

vi.mock("next/navigation", () => ({
  useParams: () => ({ slug: "acme" }),
  useRouter: () => ({ push, replace: vi.fn() }),
}));

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
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const acmeClone: WorkflowTemplate = {
  id: "tmpl-9",
  name: "Acme Onboarding",
  description: "",
  status: "ACTIVE",
  currentVersionId: undefined,
  currentVersionNo: undefined,
  customerId: "cust-1",
  clonedFromTemplateId: "tmpl-1",
};

beforeEach(() => {
  push.mockClear();
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

afterEach(cleanup);

describe("RefreshFromSourceDialog", () => {
  it("renders nothing when closed", () => {
    const { container } = render(
      <RefreshFromSourceDialog template={acmeClone} sourceName="Standard Onboarding" open={false} onClose={vi.fn()} />,
      { wrapper: makeWrapper() },
    );
    expect(container).toBeEmptyDOMElement();
  });

  /** The brief's own TDD case (Task 30, Step 1). */
  it("says plainly that tailoring is not carried across before refreshing", async () => {
    render(
      <RefreshFromSourceDialog template={acmeClone} sourceName="Standard Onboarding" open onClose={vi.fn()} />,
      { wrapper: makeWrapper() },
    );

    expect(screen.getByText(/will not carry across your changes/i)).toBeInTheDocument();
    // The confirm button stays disabled until the warning is acknowledged: refresh
    // REPLACES (spec 5.2), and discovering that afterwards means re-tailoring by hand.
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
  });

  it("enables Refresh only once the warning is acknowledged", () => {
    render(
      <RefreshFromSourceDialog template={acmeClone} sourceName="Standard Onboarding" open onClose={vi.fn()} />,
      { wrapper: makeWrapper() },
    );

    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "Refresh" })).not.toBeDisabled();

    fireEvent.click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "Refresh" })).toBeDisabled();
  });

  it("refreshes and routes straight into the new draft's editor on success", async () => {
    fetchMock.mockResolvedValue(reply({ versionId: "v-9", templateId: "tmpl-9", status: "DRAFT" }, 201));
    const onClose = vi.fn();

    render(
      <RefreshFromSourceDialog template={acmeClone} sourceName="Standard Onboarding" open onClose={onClose} />,
      { wrapper: makeWrapper() },
    );

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/t/acme/workflows/tmpl-9/refresh",
      expect.objectContaining({ method: "POST" }),
    );
    expect(push).toHaveBeenCalledWith("/t/acme/admin/workflows/tmpl-9/versions/v-9");
  });

  /** The customer template already has an open draft -- the same conflict createDraft guards against. */
  it("surfaces a 409 as a conflict message rather than a generic error", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "Already has an open draft", versionId: "open-1" }, 409));

    render(
      <RefreshFromSourceDialog template={acmeClone} sourceName="Standard Onboarding" open onClose={vi.fn()} />,
      { wrapper: makeWrapper() },
    );

    fireEvent.click(screen.getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe(
        "This template already has a draft in progress. Ask whoever started it, or check back shortly.",
      ),
    );
    expect(push).not.toHaveBeenCalled();
  });
});
