import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { setTenantSlug } from "@/lib/api/client";
import type { Permission, Role } from "@/lib/api/admin";

const { RoleEditor } = await import("./RoleEditor");

const fetchMock = vi.fn();

/**
 * A shape of the real catalog, not the whole of it: one ALL-only permission, one
 * record-scoped permission, and one org-scoped permission. Those are the three
 * cases the editor has to tell apart, and the point of every assertion here is
 * that it tells them apart by READING the catalog rather than by knowing the
 * scope vocabulary.
 */
const CATALOG: Permission[] = [
  {
    key: "customer.create",
    category: "customer",
    description: "Create customers",
    allowedScopes: ["ALL"],
  },
  {
    key: "customer.view",
    category: "customer",
    description: "View customers",
    // Alphabetical, exactly as the API sorts them — which is why "first" is ALL
    // and cannot be used as "narrowest".
    allowedScopes: ["ALL", "ASSIGNED", "DEPARTMENT", "TEAM"],
  },
  {
    key: "user.view",
    category: "identity",
    description: "View users",
    allowedScopes: ["ALL", "DEPARTMENT", "TEAM"],
  },
];

const ROLE: Role = {
  id: "role-1",
  name: "Support",
  description: "Assists customers",
  enabled: true,
  systemTemplate: false,
  grants: { "customer.view": "TEAM" },
};

function renderEditor(role: Role = ROLE, canManage = true) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<RoleEditor role={role} permissions={CATALOG} canManage={canManage} />, {
    wrapper: Wrapper,
  });
}

/** The row a permission's toggle and scope control live in. */
function rowFor(description: string): HTMLElement {
  const label = screen.getByText(description);
  const row = label.closest("li");
  if (!row) throw new Error(`no row for ${description}`);
  return row;
}

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({
    ok: true,
    status: 204,
    text: async () => "",
    json: async () => undefined,
  } as unknown as Response);
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
});

afterEach(cleanup);

describe("RoleEditor", () => {
  it("offers only the scopes a permission allows, read from the catalog", () => {
    renderEditor();

    const options = within(rowFor("View customers"))
      .getByRole("combobox")
      .querySelectorAll("option");

    expect(Array.from(options).map((option) => option.getAttribute("value"))).toEqual([
      "ALL",
      "ASSIGNED",
      "DEPARTMENT",
      "TEAM",
    ]);
  });

  /**
   * The brief's sharpest requirement. An ALL-only permission shows a single
   * option, not a disabled dropdown of four — a select with one entry is a
   * control that lies about being a choice, and a disabled one reads as broken.
   */
  it("shows an ALL-only permission's scope as a value rather than a control", () => {
    renderEditor({ ...ROLE, grants: { "customer.create": "ALL" } });

    const row = within(rowFor("Create customers"));
    expect(row.queryByRole("combobox")).toBeNull();
    expect(row.getByText("All records")).not.toBeNull();
  });

  it("offers three scopes for an org-scoped permission, not four", () => {
    renderEditor({ ...ROLE, grants: { "user.view": "TEAM" } });

    const options = within(rowFor("View users")).getByRole("combobox").querySelectorAll("option");
    expect(Array.from(options).map((option) => option.getAttribute("value"))).toEqual([
      "ALL",
      "DEPARTMENT",
      "TEAM",
    ]);
  });

  it("marks each permission with a switch whose aria-checked reflects the grant", () => {
    renderEditor();

    expect(within(rowFor("View customers")).getByRole("switch").getAttribute("aria-checked"))
      .toBe("true");
    expect(within(rowFor("Create customers")).getByRole("switch").getAttribute("aria-checked"))
      .toBe("false");
  });

  /**
   * Granting must not hand out the widest authority available on one click. The
   * catalog sorts allowedScopes alphabetically, so the first entry is ALL — a
   * naive `allowedScopes[0]` would grant tenant-wide access to a record-scoped
   * permission the moment someone flipped a toggle.
   */
  it("grants a record-scoped permission at its narrowest scope, never ALL", () => {
    renderEditor({ ...ROLE, grants: {} });

    fireEvent.click(within(rowFor("View customers")).getByRole("switch"));

    const select = within(rowFor("View customers")).getByRole("combobox") as HTMLSelectElement;
    expect(select.value).toBe("ASSIGNED");
  });

  it("sends the whole grant map on save, because the endpoint is a full replace", async () => {
    renderEditor();

    fireEvent.click(within(rowFor("Create customers")).getByRole("switch"));
    fireEvent.click(screen.getByRole("button", { name: "Save grants" }));

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalled());

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/t/acme/admin/roles/role-1/grants");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual({
      "customer.view": "TEAM",
      "customer.create": "ALL",
    });
  });

  /**
   * The API has no rename and no re-description, so a field that looked editable
   * would lie about what saving does.
   */
  it("renders the inspector's identity fields readonly", () => {
    renderEditor();

    for (const label of ["Name", "Description", "Role ID"]) {
      const field = screen.getByLabelText(label) as HTMLInputElement;
      expect(field.readOnly).toBe(true);
      expect(field.disabled).toBe(false);
    }
  });

  /**
   * The panel carries two mutations and used to report only one. A rejected
   * disable left the switch snapping back to its old position with no
   * explanation — an interface that appears to have ignored the user, which is
   * worse than an error.
   */
  it("reports a failed enable/disable, not only a failed save", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      String(url).includes("/disable")
        ? ({ ok: false, status: 500, text: async () => "boom" } as unknown as Response)
        : ({ ok: true, status: 204, text: async () => "", json: async () => undefined } as unknown as Response),
    );

    renderEditor();
    fireEvent.click(screen.getByRole("switch", { name: "Role enabled" }));

    await vi.waitFor(() =>
      expect(screen.getByRole("alert").textContent).toBe(
        "That change could not be saved. The role is unchanged.",
      ),
    );
  });

  it("offers no save affordance to a user who cannot manage roles", () => {
    renderEditor(ROLE, false);

    expect(screen.queryByRole("button", { name: "Save grants" })).toBeNull();
    expect(within(rowFor("View customers")).getByRole("switch").hasAttribute("disabled")).toBe(true);
  });
});
