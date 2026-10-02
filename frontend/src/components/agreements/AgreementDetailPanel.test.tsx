import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { AgreementDetail } from "@/lib/api/agreements";
import { AgreementDetailPanel } from "./AgreementDetailPanel";

const { downloadDocumentVersion } = vi.hoisted(() => ({ downloadDocumentVersion: vi.fn() }));
vi.mock("@/lib/api/documents", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/documents")>();
  return { ...actual, downloadDocumentVersion };
});

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

afterEach(cleanup);
const fetchMock = vi.fn();
const onClose = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return render(<AgreementDetailPanel id="a-1" onClose={onClose} />, { wrapper: Wrapper });
}

const draftDetail: AgreementDetail = {
  agreement: {
    id: "a-1",
    caseId: "c-1",
    customerId: "cust-1",
    name: "Master Services Agreement",
    recordMode: "FILE_BACKED",
    status: "DRAFT",
    displayStatus: "DRAFT",
    noticePeriodDays: 30,
    lockVersion: 3,
  },
  signatories: [{ id: "s1", kind: "CONTACT", contactId: "ct-1", displayName: "Dana Reyes", displayRole: "Client sponsor", sortOrder: 0, signed: false }],
  versions: [],
  signatures: [],
};

const sentDetail: AgreementDetail = {
  agreement: { ...draftDetail.agreement, status: "SENT", displayStatus: "SENT", latestVersionNumber: 1, lockVersion: 9 },
  signatories: [
    { id: "s1", kind: "CONTACT", contactId: "ct-1", displayName: "Dana Reyes", displayRole: "Client sponsor", sortOrder: 0, signed: true },
    { id: "s2", kind: "INTERNAL", userId: "u-1", displayName: "Omar Fadel", displayRole: "Account executive", sortOrder: 1, signed: false },
  ],
  versions: [{ id: "v1", versionNumber: 1, contentSha256: "a1b2c3d4e5f60718293a", reviewDecision: "APPROVE" }],
  signatures: [{ id: "g1", signatoryId: "s1", signedOn: "2026-08-14", method: "MANUAL" }],
};

let detail: AgreementDetail;

beforeEach(() => {
  permissions = { "agreement.manage": ["ALL"], "document.view": ["ALL"], "contact.view": ["ALL"], "user.view": ["ALL"] };
  detail = draftDetail;
  onClose.mockReset();
  downloadDocumentVersion.mockReset();
  downloadDocumentVersion.mockResolvedValue(undefined);
  fetchMock.mockReset();
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === "PATCH") return Promise.resolve(reply({ detail: "conflict" }, 409));
    if (url.endsWith("/agreements/a-1")) return Promise.resolve(reply(detail));
    if (url.endsWith("/documents/doc-1")) return Promise.resolve(reply({ id: "doc-1", name: "MSA file", currentVersionNumber: 3 }));
    if (url.includes("/contacts")) return Promise.resolve(reply([]));
    if (url.includes("/admin/users")) return Promise.resolve(reply({ content: [] }));
    return Promise.resolve(reply({}));
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

const detailGets = () => fetchMock.mock.calls.filter(([u, i]) => (u as string).endsWith("/agreements/a-1") && !(i as RequestInit | undefined)?.method).length;

describe("AgreementDetailPanel", () => {
  it("shows a loading skeleton, then the name, status and editable fields for a DRAFT with agreement.manage", async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const first = renderPanel();
    expect(first.container.querySelector("[aria-busy='true']")).not.toBeNull();
    first.unmount();
    cleanup();

    fetchMock.mockReset();
    fetchMock.mockImplementation((url: string) => Promise.resolve(url.endsWith("/agreements/a-1") ? reply(detail) : reply([])));
    renderPanel();
    await waitFor(() => expect(screen.getByRole("heading", { name: "Master Services Agreement" })).not.toBeNull());
    expect(screen.getByText("Draft")).not.toBeNull();
    expect(screen.getByLabelText("Agreement name")).not.toBeNull();
    expect(screen.getByLabelText("Effective date")).not.toBeNull();
    expect(screen.getByRole("button", { name: "Save signatories" })).not.toBeNull();
  });

  it("a DRAFT is read-only without agreement.manage", async () => {
    permissions = { "contact.view": ["ALL"] };
    renderPanel();
    await waitFor(() => expect(screen.getByRole("heading", { name: "Master Services Agreement" })).not.toBeNull());
    expect(screen.queryByLabelText("Agreement name")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Save signatories" })).toBeNull();
    expect(screen.getByText("You can view this agreement but not edit it.")).not.toBeNull();
  });

  it("everything is read-only outside DRAFT, and shows versions and signatures", async () => {
    detail = sentDetail;
    renderPanel();
    await waitFor(() => expect(screen.getByText("Sent")).not.toBeNull());
    expect(screen.queryByLabelText("Agreement name")).toBeNull();
    expect(screen.queryByLabelText("Upload file")).toBeNull();
    expect(screen.queryByRole("button", { name: "Save changes" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Save signatories" })).toBeNull();
    expect(screen.queryByRole("button", { name: /^Remove / })).toBeNull();
    expect(screen.getByText("This agreement can no longer be edited.")).not.toBeNull();
    expect(screen.getByText("a1b2c3d4e5f6")).not.toBeNull();
    expect(screen.getByText("Approved")).not.toBeNull();
    expect(screen.getByText("14 Aug 2026 via Manual record")).not.toBeNull();
    expect(screen.getByText("Pending")).not.toBeNull();
  });

  it("a 409 from a stale lockVersion shows the reload message and refetches the detail", async () => {
    renderPanel();
    await waitFor(() => expect(screen.getByLabelText("Agreement name")).not.toBeNull());
    const before = detailGets();
    fireEvent.change(screen.getByLabelText("Agreement name"), { target: { value: "Edited" } });
    fireEvent.click(screen.getByRole("button", { name: "Save changes" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Someone else changed this agreement"));
    await waitFor(() => expect(detailGets()).toBeGreaterThan(before));
  });

  it("shows an error state with retry when the detail fails to load", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(reply({ message: "boom" }, 500)));
    renderPanel();
    await waitFor(() => expect(screen.getByRole("button", { name: /try again/i })).not.toBeNull());
  });

  it("moves focus to the panel heading on open, and Escape closes it", async () => {
    renderPanel();
    const heading = await screen.findByRole("heading", { name: "Master Services Agreement" });
    await waitFor(() => expect(document.activeElement).toBe(heading));
    fireEvent.keyDown(heading, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("the close control calls onClose and focus returns to the opener on unmount", async () => {
    const opener = document.createElement("button");
    document.body.appendChild(opener);
    opener.focus();
    const view = renderPanel();
    await screen.findByRole("heading", { name: "Master Services Agreement" });
    fireEvent.click(screen.getByRole("button", { name: "Close agreement detail" }));
    expect(onClose).toHaveBeenCalledTimes(1);
    view.unmount();
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });
});

describe("AgreementDetailPanel: opening the agreement file", () => {
  const withFile = (d: AgreementDetail, over: Partial<NonNullable<AgreementDetail["agreement"]>> = {}): AgreementDetail => ({
    ...d,
    agreement: { ...d.agreement, documentId: "doc-1", ...over },
  });
  const openButton = () => screen.findByRole("button", { name: "Open file" });

  it("a DRAFT with a file offers Open file, which downloads the document's current version", async () => {
    detail = withFile(draftDetail);
    renderPanel();
    const button = await openButton();
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(downloadDocumentVersion).toHaveBeenCalledWith("doc-1", 3, "MSA file"));
  });

  it.each([
    ["SENT", { status: "SENT", displayStatus: "SENT" }],
    ["SIGNED", { status: "SIGNED", displayStatus: "SIGNED" }],
    ["EXPIRED", { status: "SIGNED", displayStatus: "EXPIRED" }],
    ["CANCELLED", { status: "CANCELLED", displayStatus: "CANCELLED" }],
  ] as const)("read-only %s still offers Open file", async (_n, over) => {
    detail = withFile(sentDetail, over);
    renderPanel();
    expect(await openButton()).not.toBeNull();
  });

  it("shows no button when the agreement has no file", async () => {
    detail = sentDetail;
    renderPanel();
    await screen.findByText("Sent");
    expect(screen.queryByRole("button", { name: "Open file" })).toBeNull();
  });

  it("hides the button, but still says a file is attached, without document.view", async () => {
    permissions = { ...permissions, "document.view": [] };
    delete permissions["document.view"];
    detail = withFile(sentDetail);
    renderPanel();
    await screen.findByText("Sent");
    expect(screen.getByText("A file is attached.")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "Open file" })).toBeNull();
  });

  it("is disabled while the download is in flight", async () => {
    let finish: () => void = () => {};
    downloadDocumentVersion.mockReturnValue(new Promise<void>((r) => (finish = r)));
    detail = withFile(sentDetail);
    renderPanel();
    const button = (await openButton()) as HTMLButtonElement;
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(button.disabled).toBe(true));
    fireEvent.click(button);
    expect(downloadDocumentVersion).toHaveBeenCalledTimes(1);
    finish();
    await waitFor(() => expect(button.disabled).toBe(false));
  });

  it("shows an error when the download fails", async () => {
    downloadDocumentVersion.mockRejectedValue(new Error("403"));
    detail = withFile(sentDetail);
    renderPanel();
    const button = (await openButton()) as HTMLButtonElement;
    await waitFor(() => expect(button.disabled).toBe(false));
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Could not open the file"));
  });
});
