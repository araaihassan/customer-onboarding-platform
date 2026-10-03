import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import {
  useCreateDepartment,
  useCreateTeam,
  useDepartments,
  useTeams,
  useUpdateDepartment,
  useUsers,
} from "@/lib/api/admin";
import { ApiError } from "@/lib/api/client";

vi.mock("@/lib/api/admin", () => ({
  useDepartments: vi.fn(),
  useTeams: vi.fn(),
  useCreateDepartment: vi.fn(),
  useCreateTeam: vi.fn(),
  useUpdateDepartment: vi.fn(),
  useUsers: vi.fn(),
}));

vi.mock("@/components/admin/TeamMembers", () => ({ TeamMembers: () => null }));

let permissions: Record<string, string[]> = {};
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user: null }) }));

const { default: OrgPage } = await import("./page");

afterEach(cleanup);

const departments = [
  { id: "dept-1", name: "Engineering", description: "Builds it", headUserId: "user-2" },
  { id: "dept-2", name: "Sales", description: "Sells it" },
];
const users = [
  { id: "user-1", fullName: "Ada Lovelace", status: "ACTIVE", userType: "INTERNAL" },
  { id: "user-2", fullName: "Grace Hopper", status: "ACTIVE", userType: "INTERNAL" },
  { id: "user-3", fullName: "Gone Person", status: "DEACTIVATED", userType: "INTERNAL" },
];

function prime(options: { updateError?: Error } = {}) {
  const updateMutate = vi.fn();
  const idle = { mutate: vi.fn(), reset: vi.fn(), isPending: false, isError: false };
  vi.mocked(useDepartments).mockReturnValue({
    data: departments,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useDepartments>);
  vi.mocked(useTeams).mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useTeams>);
  vi.mocked(useUsers).mockReturnValue({
    data: { content: users, totalElements: 3, totalPages: 1 },
  } as unknown as ReturnType<typeof useUsers>);
  vi.mocked(useCreateDepartment).mockReturnValue(idle as unknown as ReturnType<typeof useCreateDepartment>);
  vi.mocked(useCreateTeam).mockReturnValue(idle as unknown as ReturnType<typeof useCreateTeam>);
  vi.mocked(useUpdateDepartment).mockReturnValue({
    mutate: updateMutate,
    reset: vi.fn(),
    isPending: false,
    isError: Boolean(options.updateError),
    error: options.updateError ?? null,
  } as unknown as ReturnType<typeof useUpdateDepartment>);
  return { updateMutate };
}

function renderPage() {
  const client = new QueryClient();
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<OrgPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  vi.clearAllMocks();
  permissions = { "department.manage": ["ALL"], "user.view": ["ALL"] };
});

describe("OrgPage department head", () => {
  it("shows each department's head by name, or No head", () => {
    prime();
    renderPage();
    expect(screen.getByText("Head: Grace Hopper")).toBeTruthy();
    expect(screen.getByText("No head")).toBeTruthy();
  });

  it("opens an Edit dialog with name, description and head, and saves all three fields", () => {
    const { updateMutate } = prime();
    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Edit Engineering" }));

    expect((screen.getByLabelText("Name") as HTMLInputElement).value).toBe("Engineering");
    expect((screen.getByLabelText("Description") as HTMLInputElement).value).toBe("Builds it");
    const head = screen.getByLabelText("Department head") as HTMLSelectElement;
    expect(head.value).toBe("user-2");
    // Active internal users only, plus the "No head" option.
    expect(Array.from(head.options).map((o) => o.textContent)).toEqual(["No head", "Ada Lovelace", "Grace Hopper"]);

    fireEvent.change(head, { target: { value: "user-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(updateMutate).toHaveBeenCalledWith(
      { id: "dept-1", body: { name: "Engineering", description: "Builds it", headUserId: "user-1" } },
      expect.anything(),
    );
  });

  it("sends headUserId null when the head is cleared", () => {
    const { updateMutate } = prime();
    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Edit Engineering" }));
    fireEvent.change(screen.getByLabelText("Department head"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(updateMutate).toHaveBeenCalledWith(
      { id: "dept-1", body: { name: "Engineering", description: "Builds it", headUserId: null } },
      expect.anything(),
    );
  });

  it("shows the server's problem detail when the head is out of scope (404)", () => {
    prime({ updateError: new ApiError(404, JSON.stringify({ detail: "Head not found" })) });
    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Edit Engineering" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("alert").textContent).toBe("Head not found");
  });

  it("offers no Edit to an actor without department.manage", () => {
    permissions = {};
    prime();
    renderPage();
    expect(screen.queryByRole("button", { name: /^Edit/ })).toBeNull();
  });
});
