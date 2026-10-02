import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Agreement } from "@/lib/api/agreements";
import { DraftEditor } from "./DraftEditor";

afterEach(cleanup);
const fetchMock = vi.fn();
const onError = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

function renderEditor(agreement: Agreement, editable: boolean) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return render(<DraftEditor agreement={agreement} editable={editable} onError={onError} />, { wrapper: Wrapper });
}

const draft: Agreement = {
  id: "a-1",
  caseId: "c-1",
  name: "Master Services Agreement",
  recordMode: "FILE_BACKED",
  status: "DRAFT",
  displayStatus: "DRAFT",
  effectiveDate: "2026-09-01",
  expiresAt: "2027-09-01",
  noticePeriodDays: 30,
  lockVersion: 4,
};

beforeEach(() => {
  fetchMock.mockReset();
  onError.mockReset();
  fetchMock.mockResolvedValue(reply({ agreement: { ...draft, lockVersion: 5 } }));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function patchCall() {
  const call = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PATCH");
  return call ? { url: call[0] as string, body: JSON.parse((call[1] as RequestInit).body as string) } : undefined;
}

describe("DraftEditor", () => {
  it("shows editable name, dates and notice period while editable", () => {
    renderEditor(draft, true);
    expect((screen.getByLabelText("Agreement name") as HTMLInputElement).value).toBe("Master Services Agreement");
    const eff = screen.getByLabelText("Effective date") as HTMLInputElement;
    expect(eff.type).toBe("date");
    expect(eff.value).toBe("2026-09-01");
    expect((screen.getByLabelText("Notice period (days)") as HTMLInputElement).value).toBe("30");
    expect(screen.getByRole("button", { name: "Save changes" })).not.toBeNull();
  });

  it("is read-only when not editable: no inputs, values shown as text", () => {
    renderEditor(draft, false);
    expect(screen.queryByLabelText("Agreement name")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    expect(screen.getByText("Master Services Agreement")).not.toBeNull();
    expect(screen.getByText("1 Sep 2026")).not.toBeNull();
    expect(screen.getByText("30")).not.toBeNull();
    expect(screen.getAllByText("Not set").length).toBeGreaterThan(0);
  });

  it("saving sends only changed fields plus lockVersion", async () => {
    renderEditor(draft, true);
    const save = screen.getByRole("button", { name: "Save changes" }) as HTMLButtonElement;
    expect(save.disabled).toBe(true);
    fireEvent.change(screen.getByLabelText("Renewal date"), { target: { value: "2027-06-01" } });
    fireEvent.change(screen.getByLabelText("Notice period (days)"), { target: { value: "45" } });
    fireEvent.click(save);
    await waitFor(() => expect(patchCall()).toBeDefined());
    expect(patchCall()!.url).toBe("/api/t/acme/agreements/a-1");
    expect(patchCall()!.body).toEqual({ renewalDate: "2027-06-01", noticePeriodDays: 45, lockVersion: 4 });
  });

  it("clearing a date sends it in `clear`, never as an empty string", async () => {
    renderEditor(draft, true);
    fireEvent.change(screen.getByLabelText("Expiry date"), { target: { value: "" } });
    fireEvent.change(screen.getByLabelText("Notice period (days)"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(patchCall()).toBeDefined());
    const body = patchCall()!.body;
    expect(body.expiresAt).toBeUndefined();
    expect(body.noticePeriodDays).toBeUndefined();
    expect([...body.clear].sort()).toEqual(["EXPIRES_AT", "NOTICE_PERIOD_DAYS"]);
    expect(body.lockVersion).toBe(4);
  });

  it("passes the current lockVersion explicitly, including when it is 0", async () => {
    renderEditor({ ...draft, lockVersion: 0 }, true);
    fireEvent.change(screen.getByLabelText("Agreement name"), { target: { value: "Renamed" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(patchCall()).toBeDefined());
    expect(patchCall()!.body).toEqual({ name: "Renamed", lockVersion: 0 });
  });

  it("refuses a blank name without calling the API", () => {
    renderEditor(draft, true);
    fireEvent.change(screen.getByLabelText("Agreement name"), { target: { value: "  " } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    expect(screen.getByText("Name is required")).not.toBeNull();
    expect(patchCall()).toBeUndefined();
  });

  it("reports a failed save through onError", async () => {
    fetchMock.mockResolvedValue(reply({ detail: "stale" }, 409));
    renderEditor(draft, true);
    fireEvent.change(screen.getByLabelText("Agreement name"), { target: { value: "X" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(onError).toHaveBeenCalled());
    expect((onError.mock.calls[0]![0] as { status: number }).status).toBe(409);
  });

  it("the file control appears only for record modes that include a file", () => {
    const first = renderEditor({ ...draft, recordMode: "FILE_BACKED" }, true);
    expect(screen.getByLabelText("Upload file")).not.toBeNull();
    first.unmount();
    const second = renderEditor({ ...draft, recordMode: "STRUCTURED_PLUS_FILE" }, true);
    expect(screen.getByLabelText("Upload file")).not.toBeNull();
    second.unmount();
    renderEditor({ ...draft, recordMode: "STRUCTURED_ONLY" }, true);
    expect(screen.queryByLabelText("Upload file")).toBeNull();
    expect(screen.queryByLabelText("Replace file")).toBeNull();
  });

  it("uploading a file posts multipart with the current lockVersion", async () => {
    renderEditor(draft, true);
    const file = new File(["%PDF"], "msa.pdf", { type: "application/pdf" });
    fireEvent.change(screen.getByLabelText("Upload file"), { target: { files: [file] } });
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    const [url, init] = fetchMock.mock.calls[0]!;
    expect(url).toBe("/api/t/acme/agreements/a-1/file?lockVersion=4");
    expect((init as RequestInit).method).toBe("POST");
    expect((init as RequestInit).body).toBeInstanceOf(FormData);
  });

  it("hides the file control when read-only", () => {
    renderEditor(draft, false);
    expect(screen.queryByLabelText("Upload file")).toBeNull();
  });
});
