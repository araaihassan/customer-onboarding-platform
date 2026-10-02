import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { AgreementDetail } from "@/lib/api/agreements";
import { AgreementActions } from "./AgreementActions";

let permissions: Record<string, string[]> = {};
let userId = "u-me";
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: { id: userId } }) }));

afterEach(cleanup);
const fetchMock = vi.fn();
const onOpenAgreement = vi.fn();
const onStale = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

function renderActions(detail: AgreementDetail) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return render(<AgreementActions detail={detail} onOpenAgreement={onOpenAgreement} onStale={onStale} />, { wrapper: Wrapper });
}

type AgreementFields = NonNullable<AgreementDetail["agreement"]>;

function make(status: string, extra: Partial<AgreementFields> = {}, rest: Partial<AgreementDetail> = {}): AgreementDetail {
  return {
    agreement: {
      id: "a-1",
      caseId: "c-1",
      name: "MSA",
      recordMode: "FILE_BACKED",
      status,
      displayStatus: status,
      latestVersionNumber: 2,
      lockVersion: 7,
      ...extra,
    } as AgreementFields,
    signatories: [],
    versions: [
      { id: "v2", versionNumber: 2, submittedBy: "u-sub", lastEditedBy: "u-edit" },
      { id: "v1", versionNumber: 1, submittedBy: "u-old", lastEditedBy: "u-old" },
    ],
    signatures: [],
    ...rest,
  };
}

const twoSignatories = [
  { id: "s1", kind: "CONTACT", displayName: "Dana Reyes", displayRole: "Client sponsor", sortOrder: 0, signed: true },
  { id: "s2", kind: "INTERNAL", displayName: "Omar Fadel", displayRole: "Account executive", sortOrder: 1, signed: false },
  { id: "s3", kind: "INTERNAL", displayName: "Lena Haddad", displayRole: "Legal", sortOrder: 2, signed: false },
] as AgreementDetail["signatories"];

function lastBody(path: string) {
  const call = [...fetchMock.mock.calls].reverse().find(([u, i]) => (u as string).includes(path) && (i as RequestInit | undefined)?.method === "POST");
  return call ? (call[1] as RequestInit).body : undefined;
}

beforeEach(() => {
  permissions = { "agreement.manage": ["ALL"], "agreement.review": ["ALL"], "agreement.sign_record": ["ALL"] };
  userId = "u-me";
  onOpenAgreement.mockReset();
  onStale.mockReset();
  fetchMock.mockReset();
  fetchMock.mockImplementation(() => Promise.resolve(reply(make("DRAFT"))));
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("AgreementActions", () => {
  it("DRAFT shows Submit for review to a manage holder, sending the current lockVersion", async () => {
    renderActions(make("DRAFT"));
    fireEvent.click(screen.getByRole("button", { name: "Submit for review" }));
    await waitFor(() => expect(lastBody("/agreements/a-1/submit")).toBeDefined());
    expect(JSON.parse(lastBody("/agreements/a-1/submit") as string)).toEqual({ lockVersion: 7 });
  });

  it("shows no Submit without agreement.manage", () => {
    permissions = {};
    renderActions(make("DRAFT"));
    expect(screen.queryByRole("button", { name: "Submit for review" })).toBeNull();
  });

  it("UNDER_REVIEW shows Approve and Reject to a review holder; Approve sends decision and lockVersion", async () => {
    renderActions(make("UNDER_REVIEW"));
    expect(screen.getByRole("button", { name: "Reject" })).not.toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(lastBody("/versions/2/review")).toBeDefined());
    expect(JSON.parse(lastBody("/versions/2/review") as string)).toEqual({ decision: "APPROVE", lockVersion: 7 });
  });

  it("Approve and Reject are disabled with an explanation for the submitter", () => {
    userId = "u-sub";
    renderActions(make("UNDER_REVIEW"));
    expect((screen.getByRole("button", { name: "Approve" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Reject" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("You submitted this version, so someone else must review it")).not.toBeNull();
  });

  it("Approve and Reject are disabled with an explanation for the last editor", () => {
    userId = "u-edit";
    renderActions(make("UNDER_REVIEW"));
    expect((screen.getByRole("button", { name: "Approve" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Reject" }) as HTMLButtonElement).disabled).toBe(true);
    expect(screen.getByText("You last edited this version, so someone else must review it")).not.toBeNull();
  });

  it("an older version's submitter is not blocked", () => {
    userId = "u-old";
    renderActions(make("UNDER_REVIEW"));
    expect((screen.getByRole("button", { name: "Approve" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("without agreement.review there are no Approve or Reject buttons", () => {
    permissions = { "agreement.manage": ["ALL"] };
    renderActions(make("UNDER_REVIEW"));
    expect(screen.queryByRole("button", { name: "Approve" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Reject" })).toBeNull();
  });

  it("Reject requires a reason before it can be sent", async () => {
    renderActions(make("UNDER_REVIEW"));
    fireEvent.click(screen.getByRole("button", { name: "Reject" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Reject version" }));
    expect(within(dialog).getByText("This field is required")).not.toBeNull();
    expect(lastBody("/review")).toBeUndefined();
    fireEvent.change(within(dialog).getByLabelText("Reason for rejection"), { target: { value: "Wrong notice period" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Reject version" }));
    await waitFor(() => expect(lastBody("/versions/2/review")).toBeDefined());
    expect(JSON.parse(lastBody("/versions/2/review") as string)).toEqual({ decision: "REJECT", reason: "Wrong notice period", lockVersion: 7 });
  });

  it("APPROVED shows Send, with the lockVersion", async () => {
    renderActions(make("APPROVED"));
    fireEvent.click(screen.getByRole("button", { name: "Send for signature" }));
    await waitFor(() => expect(lastBody("/agreements/a-1/send")).toBeDefined());
    expect(JSON.parse(lastBody("/agreements/a-1/send") as string)).toEqual({ lockVersion: 7 });
  });

  it("SENT and AWAITING_SIGNATURE show Record signature, listing only unsigned signatories", () => {
    for (const status of ["SENT", "AWAITING_SIGNATURE"]) {
      cleanup();
      renderActions(make(status, {}, { signatories: twoSignatories }));
      fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
      const select = screen.getByLabelText("Signatory") as HTMLSelectElement;
      const names = Array.from(select.options).map((o) => o.textContent ?? "");
      expect(names.some((n) => n.includes("Dana Reyes"))).toBe(false);
      expect(names.some((n) => n.includes("Omar Fadel"))).toBe(true);
      expect(names.some((n) => n.includes("Lena Haddad"))).toBe(true);
    }
  });

  it("records a signature as multipart with the lockVersion in the JSON part", async () => {
    renderActions(make("SENT", { recordMode: "STRUCTURED_ONLY" }, { signatories: twoSignatories }));
    fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Signatory"), { target: { value: "s2" } });
    fireEvent.change(within(dialog).getByLabelText("Signed on"), { target: { value: "2026-09-01" } });
    fireEvent.change(within(dialog).getByLabelText("Method"), { target: { value: "Wet ink" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Record signature" }));
    await waitFor(() => expect(lastBody("/agreements/a-1/signatures")).toBeDefined());
    const form = lastBody("/agreements/a-1/signatures") as FormData;
    const part = form.get("signature") as Blob;
    expect(JSON.parse(await part.text())).toEqual({ signatoryId: "s2", signedOn: "2026-09-01", method: "Wet ink", lockVersion: 7 });
    expect(form.get("file")).toBeNull();
  });

  it("offers the method suggestions", () => {
    renderActions(make("SENT", {}, { signatories: twoSignatories }));
    fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
    const list = document.getElementById(screen.getByLabelText("Method").getAttribute("list") ?? "")!;
    const values = Array.from(list.querySelectorAll("option")).map((o) => o.getAttribute("value"));
    expect(values).toEqual(["Wet ink", "Signed PDF returned by email"]);
  });

  it("the last signatory of a file-including agreement requires the countersigned file", async () => {
    const one = [twoSignatories![0]!, twoSignatories![1]!];
    renderActions(make("AWAITING_SIGNATURE", { recordMode: "FILE_BACKED" }, { signatories: one }));
    fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByLabelText("Signed on"), { target: { value: "2026-09-01" } });
    fireEvent.change(within(dialog).getByLabelText("Method"), { target: { value: "Wet ink" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Record signature" }));
    expect(within(dialog).getByText("The last signature needs the countersigned file")).not.toBeNull();
    expect(lastBody("/signatures")).toBeUndefined();
    const file = new File(["pdf"], "signed.pdf", { type: "application/pdf" });
    fireEvent.change(within(dialog).getByLabelText("Countersigned file"), { target: { files: [file] } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Record signature" }));
    await waitFor(() => expect(lastBody("/signatures")).toBeDefined());
    expect((lastBody("/signatures") as FormData).get("file")).not.toBeNull();
  });

  it("a structured-only agreement does not ask for a file even for the last signatory", () => {
    renderActions(make("AWAITING_SIGNATURE", { recordMode: "STRUCTURED_ONLY" }, { signatories: [twoSignatories![1]!] }));
    fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
    expect(screen.queryByLabelText("Countersigned file")).toBeNull();
  });

  it("signedOn cannot be set in the future", () => {
    renderActions(make("SENT", { recordMode: "STRUCTURED_ONLY" }, { signatories: twoSignatories }));
    fireEvent.click(screen.getByRole("button", { name: "Record signature" }));
    const dialog = screen.getByRole("dialog");
    const date = within(dialog).getByLabelText("Signed on") as HTMLInputElement;
    const now = new Date();
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
    expect(date.max).toBe(today);
    fireEvent.change(date, { target: { value: "2999-01-01" } });
    fireEvent.change(within(dialog).getByLabelText("Method"), { target: { value: "Wet ink" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Record signature" }));
    expect(within(dialog).getByText("A signature cannot be dated in the future")).not.toBeNull();
    expect(lastBody("/signatures")).toBeUndefined();
  });

  it("Cancel and replace appears before SIGNED, requires a reason, and opens the successor afterwards", async () => {
    for (const status of ["DRAFT", "UNDER_REVIEW", "APPROVED", "SENT", "AWAITING_SIGNATURE"]) {
      cleanup();
      renderActions(make(status));
      expect(screen.getByRole("button", { name: "Cancel and replace" })).not.toBeNull();
    }
    cleanup();
    fetchMock.mockImplementation(() => Promise.resolve(reply(make("DRAFT", { id: "a-2", lockVersion: 0 }))));
    renderActions(make("SENT"));
    fireEvent.click(screen.getByRole("button", { name: "Cancel and replace" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel agreement" }));
    expect(within(dialog).getByText("This field is required")).not.toBeNull();
    expect(lastBody("/cancel")).toBeUndefined();
    fireEvent.change(within(dialog).getByLabelText("Reason for cancelling"), { target: { value: "Terms changed" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel agreement" }));
    await waitFor(() => expect(onOpenAgreement).toHaveBeenCalledWith("a-2"));
    expect(JSON.parse(lastBody("/agreements/a-1/cancel") as string)).toEqual({ reason: "Terms changed", lockVersion: 7 });
  });

  it("SIGNED, EXPIRED and CANCELLED show no lifecycle actions", () => {
    for (const [status, display] of [["SIGNED", "SIGNED"], ["SIGNED", "EXPIRED"], ["CANCELLED", "CANCELLED"]] as const) {
      cleanup();
      const { container } = renderActions(make(status, { displayStatus: display }));
      expect(container.querySelectorAll("button")).toHaveLength(0);
    }
  });

  it("a server 409 SelfReview detail is shown verbatim", async () => {
    const detail = "You submitted or last edited this version, so someone else must review it.";
    fetchMock.mockImplementation(() => Promise.resolve(reply({ detail }, 409)));
    renderActions(make("UNDER_REVIEW"));
    fireEvent.click(screen.getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toBe(detail));
    expect(onStale).toHaveBeenCalled();
  });
});
