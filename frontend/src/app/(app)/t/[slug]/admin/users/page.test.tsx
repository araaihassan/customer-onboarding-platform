import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { Me } from "@/lib/auth/types";
import {
  useAssignRole,
  useDeactivateUser,
  useDepartments,
  useInviteUser,
  useRoles,
  useUnassignRole,
  useUpdateUser,
  useUsers,
} from "@/lib/api/admin";

vi.mock("@/lib/api/admin", () => ({
  useUsers: vi.fn(),
  useRoles: vi.fn(),
  useInviteUser: vi.fn(),
  useDeactivateUser: vi.fn(),
  useAssignRole: vi.fn(),
  useUnassignRole: vi.fn(),
  useDepartments: vi.fn(),
  useUpdateUser: vi.fn(),
}));

let permissions: Record<string, string[]> = {};
let user: Me | null = null;

// useHasPermission reads useAuth() internally, so mocking this one module
// covers both `useHasPermission` and this file's own `useAuth().user` read --
// exactly the shape Task 8's brief describes (department.manage gates the
// full picker; user?.departmentId is the fallback with zero new plumbing).
vi.mock("@/lib/auth/useAuth", () => ({ useAuth: () => ({ permissions, user }) }));

const { default: UsersPage } = await import("./page");

afterEach(cleanup);

const target = {
  id: "user-1",
  email: "target@example.com",
  fullName: "Target Person",
  status: "ACTIVE" as const,
  departmentId: "dept-1",
  roleIds: [],
};

const departments = [
  { id: "dept-1", name: "Engineering" },
  { id: "dept-2", name: "Sales" },
];

function primeHooks(options: {
  inviteMutate?: ReturnType<typeof vi.fn>;
  updateMutate?: ReturnType<typeof vi.fn>;
  departmentsEnabled?: boolean;
}) {
  const inviteMutate = options.inviteMutate ?? vi.fn();
  const updateMutate = options.updateMutate ?? vi.fn();

  vi.mocked(useUsers).mockReturnValue({
    data: { content: [target], totalElements: 1, totalPages: 1 },
    isLoading: false,
    isError: false,
    isFetching: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useUsers>);
  vi.mocked(useRoles).mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useRoles>);
  vi.mocked(useInviteUser).mockReturnValue({
    mutate: inviteMutate,
    reset: vi.fn(),
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useInviteUser>);
  vi.mocked(useUpdateUser).mockReturnValue({
    mutate: updateMutate,
    reset: vi.fn(),
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useUpdateUser>);
  vi.mocked(useDeactivateUser).mockReturnValue({
    mutate: vi.fn(),
    reset: vi.fn(),
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useDeactivateUser>);
  vi.mocked(useAssignRole).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useAssignRole>);
  vi.mocked(useUnassignRole).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
    isError: false,
  } as unknown as ReturnType<typeof useUnassignRole>);
  vi.mocked(useDepartments).mockImplementation((enabled = true) => ({
    data: enabled ? departments : undefined,
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  }) as unknown as ReturnType<typeof useDepartments>);

  return { inviteMutate, updateMutate };
}

function renderPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  }
  return render(<UsersPage />, { wrapper: Wrapper });
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("UsersPage invite form — department picker", () => {
  it("offers the full department list and submits the chosen id, for an actor holding department.manage", () => {
    permissions = { "user.view": ["ALL"], "user.manage": ["ALL"], "department.manage": ["ALL"] };
    user = { id: "actor-1", fullName: "Actor", email: "actor@example.com" };
    const { inviteMutate } = primeHooks({});

    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Invite user" }));

    const select = screen.getByLabelText("Department") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.textContent)).toEqual([
      "No department",
      "Engineering",
      "Sales",
    ]);

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Full name"), { target: { value: "New Person" } });
    fireEvent.change(select, { target: { value: "dept-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Send invitation" }));

    expect(inviteMutate).toHaveBeenCalledWith(
      { email: "new@example.com", fullName: "New Person", departmentId: "dept-2" },
      expect.anything(),
    );
  });

  it("submits the actor's own departmentId with no visible picker, for a DEPARTMENT-scoped holder without department.manage", () => {
    permissions = { "user.view": ["DEPARTMENT"], "user.manage": ["DEPARTMENT"] };
    user = { id: "actor-2", fullName: "Narrow Actor", email: "narrow@example.com", departmentId: "dept-1" };
    // useDepartments must never even be called successfully for this actor --
    // it is gated on department.manage, which this actor does not hold -- so
    // the mock returns no data, proving the fallback does not depend on it.
    const { inviteMutate } = primeHooks({});

    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Invite user" }));

    expect(screen.queryByLabelText("Department")).toBeNull();

    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "recruit@example.com" } });
    fireEvent.change(screen.getByLabelText("Full name"), { target: { value: "A Recruit" } });
    fireEvent.click(screen.getByRole("button", { name: "Send invitation" }));

    expect(inviteMutate).toHaveBeenCalledWith(
      { email: "recruit@example.com", fullName: "A Recruit", departmentId: "dept-1" },
      expect.anything(),
    );
  });
});

describe("UsersPage edit form", () => {
  it("lets an actor holding department.manage move a user to a different department", () => {
    permissions = {
      "user.view": ["ALL"],
      "user.manage": ["ALL"],
      "department.manage": ["ALL"],
    };
    user = { id: "actor-1", fullName: "Actor", email: "actor@example.com" };
    const { updateMutate } = primeHooks({});

    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Edit Target Person" }));

    const select = screen.getByLabelText("Department") as HTMLSelectElement;
    expect(select.value).toBe("dept-1");
    fireEvent.change(select, { target: { value: "dept-2" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(updateMutate).toHaveBeenCalledWith(
      { id: "user-1", body: { fullName: "Target Person", departmentId: "dept-2" } },
      expect.anything(),
    );
  });

  it("resends the user's current departmentId unchanged when the actor cannot manage departments", () => {
    permissions = { "user.view": ["DEPARTMENT"], "user.manage": ["DEPARTMENT"] };
    user = { id: "actor-2", fullName: "Narrow Actor", email: "narrow@example.com", departmentId: "dept-1" };
    const { updateMutate } = primeHooks({});

    renderPage();
    fireEvent.click(screen.getByRole("button", { name: "Edit Target Person" }));

    expect(screen.queryByLabelText("Department")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(updateMutate).toHaveBeenCalledWith(
      { id: "user-1", body: { fullName: "Target Person", departmentId: "dept-1" } },
      expect.anything(),
    );
  });
});
