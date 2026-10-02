import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import type { Agreement, AgreementSignatory } from "@/lib/api/agreements";
import { SignatoryEditor } from "./SignatoryEditor";

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions }) }));

afterEach(cleanup);
const fetchMock = vi.fn();
const onError = vi.fn();

function reply(body: unknown, status = 200) {
  return { ok: status < 400, status, text: async () => JSON.stringify(body), json: async () => body } as unknown as Response;
}

const agreement: Agreement = { id: "a-1", caseId: "c-1", customerId: "cust-1", status: "DRAFT", displayStatus: "DRAFT", lockVersion: 7 };

const existing: AgreementSignatory[] = [
  { id: "s1", kind: "CONTACT", contactId: "ct-1", displayName: "Dana Reyes", displayRole: "Client sponsor", sortOrder: 0, signed: false },
  { id: "s2", kind: "INTERNAL", userId: "u-1", displayName: "Omar Fadel", displayRole: "Account executive", sortOrder: 1, signed: false },
];

function renderEditor(editable: boolean, signatories = existing) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const Wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  return render(<SignatoryEditor agreement={agreement} signatories={signatories} editable={editable} onError={onError} />, { wrapper: Wrapper });
}

function putCall() {
  const call = fetchMock.mock.calls.find(([, init]) => (init as RequestInit | undefined)?.method === "PUT");
  return call ? { url: call[0] as string, body: JSON.parse((call[1] as RequestInit).body as string) } : undefined;
}

beforeEach(() => {
  permissions = { "agreement.manage": ["ALL"], "contact.view": ["ALL"], "user.view": ["ALL"] };
  fetchMock.mockReset();
  onError.mockReset();
  fetchMock.mockImplementation((url: string, init?: RequestInit) => {
    if (init?.method === "PUT") return Promise.resolve(reply({ agreement: { ...agreement, lockVersion: 8 }, signatories: [] }));
    if (url.includes("/customers/cust-1/contacts")) {
      return Promise.resolve(
        reply([
          { id: "ct-1", customerId: "cust-1", fullName: "Dana Reyes", status: "ACTIVE" },
          { id: "ct-2", customerId: "cust-1", fullName: "Priya Nair", status: "ACTIVE" },
          { id: "ct-3", customerId: "cust-1", fullName: "Retired Contact", status: "INACTIVE" },
        ]),
      );
    }
    if (url.includes("/admin/users")) {
      return Promise.resolve(
        reply({
          content: [
            { id: "u-1", fullName: "Omar Fadel", userType: "INTERNAL", status: "ACTIVE" },
            { id: "u-2", fullName: "Lena Park", userType: "INTERNAL", status: "ACTIVE" },
            { id: "u-3", fullName: "Gone User", userType: "INTERNAL", status: "DEACTIVATED" },
          ],
        }),
      );
    }
    return Promise.resolve(reply({}));
  });
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

function names() {
  return screen.getAllByRole("listitem").map((li) => li.querySelector("[data-testid='signatory-name']")?.textContent);
}

describe("SignatoryEditor", () => {
  it("adds, reorders and removes, then PUTs the whole list with lockVersion", async () => {
    renderEditor(true);
    expect(names()).toEqual(["Dana Reyes", "Omar Fadel"]);

    // reorder: move Omar up
    fireEvent.click(screen.getByRole("button", { name: "Move Omar Fadel up" }));
    expect(names()).toEqual(["Omar Fadel", "Dana Reyes"]);

    // add a contact
    await waitFor(() => expect(screen.getByRole("option", { name: "Priya Nair" })).not.toBeNull());
    fireEvent.change(screen.getByLabelText("Person"), { target: { value: "ct-2" } });
    fireEvent.change(screen.getByLabelText("Role on this agreement"), { target: { value: "Legal counsel" } });
    fireEvent.click(screen.getByRole("button", { name: "Add signatory" }));
    expect(names()).toEqual(["Omar Fadel", "Dana Reyes", "Priya Nair"]);

    // remove Dana
    fireEvent.click(screen.getByRole("button", { name: "Remove Dana Reyes" }));
    expect(names()).toEqual(["Omar Fadel", "Priya Nair"]);

    fireEvent.click(screen.getByRole("button", { name: "Save signatories" }));
    await waitFor(() => expect(putCall()).toBeDefined());
    expect(putCall()!.url).toBe("/api/t/acme/agreements/a-1/signatories");
    expect(putCall()!.body).toEqual({
      signatories: [
        { kind: "INTERNAL", userId: "u-1", displayRole: "Account executive" },
        { kind: "CONTACT", contactId: "ct-2", displayRole: "Legal counsel" },
      ],
      lockVersion: 7,
    });
  });

  it("offers only this customer's active contacts as contact signatories", async () => {
    renderEditor(true, []);
    await waitFor(() => expect(screen.getByRole("option", { name: "Priya Nair" })).not.toBeNull());
    expect(screen.getByRole("option", { name: "Dana Reyes" })).not.toBeNull();
    expect(screen.queryByRole("option", { name: "Retired Contact" })).toBeNull();
    const urls = fetchMock.mock.calls.map(([u]) => u as string);
    expect(urls.some((u) => u.includes("/customers/cust-1/contacts"))).toBe(true);
  });

  it("offers active internal users when the signatory type is switched to internal", async () => {
    renderEditor(true, []);
    fireEvent.change(screen.getByLabelText("Signatory type"), { target: { value: "INTERNAL" } });
    await waitFor(() => expect(screen.getByRole("option", { name: "Lena Park" })).not.toBeNull());
    expect(screen.queryByRole("option", { name: "Gone User" })).toBeNull();
  });

  it("hides the internal-signatory option without user.view, and never fetches users", async () => {
    permissions = { "agreement.manage": ["ALL"], "contact.view": ["ALL"] };
    renderEditor(true, []);
    const kind = screen.queryByLabelText("Signatory type") as HTMLSelectElement | null;
    const options = kind ? within(kind).queryAllByRole("option").map((o) => o.textContent) : [];
    expect(options).not.toContain("Internal user");
    await waitFor(() => expect(screen.getByRole("option", { name: "Priya Nair" })).not.toBeNull());
    expect(fetchMock.mock.calls.some(([u]) => (u as string).includes("/admin/users"))).toBe(false);
  });

  it("keeps Save disabled until something changes and requires a role to add", () => {
    renderEditor(true);
    expect((screen.getByRole("button", { name: "Save signatories" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Add signatory" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("is read-only when not editable: names and roles only, no controls", () => {
    renderEditor(false);
    expect(screen.getByText("Dana Reyes")).not.toBeNull();
    expect(screen.getByText("Client sponsor")).not.toBeNull();
    expect(screen.queryByRole("button")).toBeNull();
    expect(screen.queryByLabelText("Person")).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("reports a failed save through onError", async () => {
    fetchMock.mockImplementation((_url: string, init?: RequestInit) =>
      Promise.resolve(init?.method === "PUT" ? reply({ detail: "stale" }, 409) : reply([])),
    );
    renderEditor(true);
    fireEvent.click(screen.getByRole("button", { name: "Remove Dana Reyes" }));
    fireEvent.click(screen.getByRole("button", { name: "Save signatories" }));
    await waitFor(() => expect(onError).toHaveBeenCalled());
    expect((onError.mock.calls[0]![0] as { status: number }).status).toBe(409);
  });
});
