import { describe, it, expect, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClientProvider, QueryClient } from "@tanstack/react-query";
import { TeamMembers } from "./TeamMembers";
import { useTeamMembers, useRemoveTeamMember, useAddTeamMember, useUsers } from "@/lib/api/admin";
import { useHasPermission } from "@/lib/auth/useHasPermission";

// Mock the API hooks
vi.mock("@/lib/api/admin", () => ({
  useTeamMembers: vi.fn(),
  useRemoveTeamMember: vi.fn(),
  useAddTeamMember: vi.fn(),
  useUsers: vi.fn(),
  adminKeys: {
    teamMembers: (id: string) => ["teamMembers", id],
  },
}));

vi.mock("@/lib/auth/useHasPermission", () => ({
  useHasPermission: vi.fn(),
}));

vi.mock("@/lib/i18n", () => ({
  t: (key: string) => key,
}));

describe("TeamMembers", () => {
  const mockTeam = {
    id: "team-123",
    name: "Test Team",
    description: "Test Description",
    departmentId: null,
  };

  const mockMembers = [
    { userId: "user-1", fullName: "Alice", email: "alice@example.com" },
    { userId: "user-2", fullName: "Bob", email: "bob@example.com" },
  ];

  const createWrapper = () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    return ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };

  it("renders empty state when team has no members", () => {
    vi.mocked(useHasPermission).mockReturnValue(true);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: [],
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as any);
    vi.mocked(useRemoveTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useAddTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useUsers).mockReturnValue({
      data: { content: [] },
      isLoading: false,
    } as any);

    render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    expect(screen.getByText("admin.team.members.empty")).toBeInTheDocument();
  });

  it("renders members list", () => {
    vi.mocked(useHasPermission).mockReturnValue(true);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: mockMembers,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as any);
    vi.mocked(useRemoveTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useAddTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useUsers).mockReturnValue({
      data: { content: [] },
      isLoading: false,
    } as any);

    render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    expect(screen.getByText("Alice")).toBeInTheDocument();
    expect(screen.getByText("Bob")).toBeInTheDocument();
  });

  it("removes a member when remove button is clicked", async () => {
    const user = userEvent.setup();
    const removeMutate = vi.fn();

    vi.mocked(useHasPermission).mockReturnValue(true);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: mockMembers,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as any);
    vi.mocked(useRemoveTeamMember).mockReturnValue({
      mutate: removeMutate,
      isPending: false,
    } as any);
    vi.mocked(useAddTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useUsers).mockReturnValue({
      data: { content: [] },
      isLoading: false,
    } as any);

    render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    const removeButtons = screen.getAllByRole("button", {
      name: new RegExp("admin.team.members.remove"),
    });
    await user.click(removeButtons[0]);

    expect(removeMutate).toHaveBeenCalledWith({
      teamId: "team-123",
      userId: "user-1",
    });
  });

  it("hides add button without team.manage permission", () => {
    vi.mocked(useHasPermission).mockReturnValue(false);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: mockMembers,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    } as any);
    vi.mocked(useRemoveTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useAddTeamMember).mockReturnValue({
      mutate: vi.fn(),
      isPending: false,
    } as any);
    vi.mocked(useUsers).mockReturnValue({
      data: { content: [] },
      isLoading: false,
    } as any);

    render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    expect(screen.queryByRole("button", { name: /admin.team.members.add/ })).not.toBeInTheDocument();
  });
});
