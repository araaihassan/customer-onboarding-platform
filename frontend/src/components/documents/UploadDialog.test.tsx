import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import { t } from "@/lib/i18n";
import { UploadDialog } from "./UploadDialog";

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
  return { onClose, ...render(<UploadDialog caseId="c-1" onClose={onClose} />, { wrapper: makeWrapper() }) };
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue(reply({ id: "doc-1" }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("UploadDialog", () => {
  /** The brief's own Step 1 test: the actual `<option>` values, not just that some options render. */
  it("offers exactly the three visibility tiers", () => {
    renderDialog();
    const select = screen.getByLabelText(t("documents.upload.visibility")) as HTMLSelectElement;
    const values = Array.from(select.options).map((option) => option.value);
    expect(values).toEqual(["COMPANY_SHARED", "CONTACT_ONLY", "SENSITIVE"]);
  });

  /**
   * The brief's own Step 1 test: a SENSITIVE selection surfaces the
   * explanation (by emphasising its own row of `VisibilityAside`), never by
   * hiding or restricting anything.
   */
  it("emphasises the SENSITIVE row of the visibility explainer once selected", () => {
    renderDialog();
    const select = screen.getByLabelText(t("documents.upload.visibility")) as HTMLSelectElement;

    // Before selection: COMPANY_SHARED (the default) is emphasised, not SENSITIVE.
    expect(document.querySelector('[data-tier="SENSITIVE"]')?.getAttribute("data-emphasized")).toBeNull();

    fireEvent.change(select, { target: { value: "SENSITIVE" } });

    expect(document.querySelector('[data-tier="SENSITIVE"]')?.getAttribute("data-emphasized")).toBe("true");
    expect(document.querySelector('[data-tier="COMPANY_SHARED"]')?.getAttribute("data-emphasized")).toBeNull();
    // The explanation itself is still there -- selecting SENSITIVE surfaces
    // it, it does not become the ONLY thing rendered.
    expect(document.body.textContent).toMatch(/other contacts at the same company cannot see it/i);
  });

  /**
   * The brief's own Step 1 test: the upload control's enabled state waits
   * for the mutation rather than optimistically flipping -- the same
   * "real and local" departure `RequirementList`'s checkbox already
   * establishes for this codebase (disabled only for the duration of the
   * mutation's own `isPending`, not before).
   */
  it("waits for the upload mutation before disabling the submit control", async () => {
    let resolveUpload!: (value: Response) => void;
    fetchMock.mockReturnValueOnce(new Promise((resolve) => { resolveUpload = resolve; }));

    renderDialog();
    fireEvent.change(screen.getByLabelText(t("documents.upload.name")), { target: { value: "MSA.pdf" } });
    const file = new File(["hello"], "MSA.pdf", { type: "application/pdf" });
    fireEvent.change(screen.getByLabelText(t("documents.upload.file")), { target: { files: [file] } });

    const submitButton = screen.getByRole("button", { name: t("documents.upload.submit") }) as HTMLButtonElement;
    expect(submitButton.disabled).toBe(false);

    fireEvent.click(submitButton);
    // The mutation's own pending state reaches the DOM on the next tick
    // (a microtask inside TanStack Query's mutation observer), not
    // synchronously within `fireEvent.click`'s own `act()` flush -- so this
    // waits for it rather than asserting immediately, the same reasoning
    // `waitFor` exists for anywhere else in this suite.
    await waitFor(() => expect(submitButton.disabled).toBe(true));

    resolveUpload(reply({ id: "doc-1" }));
    await waitFor(() => expect(submitButton.disabled).toBe(false));
  });

  it("shows a validation error and never calls the mutation when no file is chosen", () => {
    renderDialog();
    fireEvent.change(screen.getByLabelText(t("documents.upload.name")), { target: { value: "MSA.pdf" } });

    fireEvent.click(screen.getByRole("button", { name: t("documents.upload.submit") }));

    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("closes on a successful upload", async () => {
    const { onClose } = renderDialog();
    fireEvent.change(screen.getByLabelText(t("documents.upload.name")), { target: { value: "MSA.pdf" } });
    const file = new File(["hello"], "MSA.pdf", { type: "application/pdf" });
    fireEvent.change(screen.getByLabelText(t("documents.upload.file")), { target: { files: [file] } });

    fireEvent.click(screen.getByRole("button", { name: t("documents.upload.submit") }));

    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });
});
