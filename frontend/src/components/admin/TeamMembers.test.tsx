import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TeamMembers } from "./TeamMembers";
import { useAddTeamMember, useRemoveTeamMember, useTeamMembers, useUsers } from "@/lib/api/admin";
import { useHasPermission } from "@/lib/auth/useHasPermission";

vi.mock("@/lib/api/admin", () => ({
  useTeamMembers: vi.fn(),
  useRemoveTeamMember: vi.fn(),
  useAddTeamMember: vi.fn(),
  useUsers: vi.fn(),
  adminKeys: { teamMembers: (id: string) => ["teamMembers", id] },
}));

vi.mock("@/lib/auth/useHasPermission", () => ({ useHasPermission: vi.fn() }));

vi.mock("@/lib/i18n", () => ({ t: (key: string) => key }));

/**
 * vitest.config.mts leaves `globals` at its default of false, so there is no
 * global `afterEach` for @testing-library/react to hook when it is imported and
 * its auto-cleanup never registers. Without this line every render in this file
 * stays mounted in document.body for the rest of the file, `screen` queries
 * match the earliest test's leftover markup, and a click lands on a component
 * still holding a previous test's (by then cleared) mock. Every other component
 * test in this repository carries the same line for the same reason.
 */
afterEach(cleanup);

describe("TeamMembers", () => {
  const team = { id: "team-123", name: "Test Team", description: "Test Description" };
  const alice = { userId: "user-1", fullName: "Alice", email: "alice@example.com" };
  const charlie = { id: "user-3", fullName: "Charlie", email: "charlie@example.com" };

  const remove = vi.fn();
  const add = vi.fn();

  /**
   * One place that primes all four hooks, so a test states only the thing it is
   * about. The `as unknown as` casts are the shape this repository already uses
   * for partial mocks of library return types (see ContactList.test.tsx).
   */
  function primeHooks(options: {
    canManage?: boolean;
    members?: typeof alice[];
    isLoading?: boolean;
    isError?: boolean;
    isRemoving?: boolean;
  }) {
    const { canManage = true, members = [], isLoading = false, isError = false } = options;
    vi.mocked(useHasPermission).mockReturnValue(canManage);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: members,
      isLoading,
      isError,
      refetch: vi.fn(),
    } as unknown as ReturnType<typeof useTeamMembers>);
    vi.mocked(useRemoveTeamMember).mockReturnValue({
      mutate: remove,
      isPending: options.isRemoving ?? false,
    } as unknown as ReturnType<typeof useRemoveTeamMember>);
    vi.mocked(useAddTeamMember).mockReturnValue({
      mutate: add,
      isPending: false,
    } as unknown as ReturnType<typeof useAddTeamMember>);
    vi.mocked(useUsers).mockReturnValue({
      data: { content: [charlie] },
      isLoading: false,
    } as unknown as ReturnType<typeof useUsers>);
  }

  let client: QueryClient;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
  });

  function renderMembers() {
    function Wrapper({ children }: { children: ReactNode }) {
      return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
    }
    return render(<TeamMembers team={team} />, { wrapper: Wrapper });
  }

  it("renders the empty state when the team has no members", () => {
    primeHooks({ members: [] });
    const { container } = renderMembers();

    expect(container.textContent).toContain("admin.team.members.empty");
  });

  it("renders a member with the name and the machine-readable address", () => {
    primeHooks({ members: [alice] });
    const { container } = renderMembers();

    expect(container.textContent).toContain("Alice");
    expect(container.textContent).toContain("alice@example.com");

    // One member, so exactly one remove button. `toBeGreaterThan(0)` here would
    // pass just as happily on rows leaked in from another test.
    expect(
      screen.getAllByRole("button", { name: "admin.team.members.remove" }),
    ).toHaveLength(1);
  });

  it("hides the add control without team.manage", () => {
    primeHooks({ canManage: false });
    const { container } = renderMembers();

    expect(container.textContent).toContain("admin.org.noAccess");
    expect(container.textContent).not.toContain("admin.team.members.add");
  });

  it("clicking remove invokes the remove mutation with the right ids", () => {
    primeHooks({ members: [alice] });
    renderMembers();

    // getByRole, not getAllByRole: the singular query throwing on a second match
    // is itself the guard against the leaked DOM described above.
    const button = screen.getByRole("button", { name: "admin.team.members.remove" });
    expect((button as HTMLButtonElement).disabled).toBe(false);

    fireEvent.click(button);

    expect(remove).toHaveBeenCalledWith({ teamId: team.id, userId: alice.userId });
  });

  it("disables remove while a removal is in flight", () => {
    primeHooks({ members: [alice], isRemoving: true });
    renderMembers();

    const button = screen.getByRole("button", { name: "admin.team.members.remove" });
    expect((button as HTMLButtonElement).disabled).toBe(true);

    fireEvent.click(button);

    expect(remove).not.toHaveBeenCalled();
  });
});
