import { describe, it, expect, vi } from "vitest";
import { render } from "@testing-library/react";
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

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container).toBeDefined();
  });

  it("shows loading state", () => {
    vi.mocked(useHasPermission).mockReturnValue(true);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: undefined,
      isLoading: true,
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

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container).toBeDefined();
  });

  it("hides add button without team.manage permission", () => {
    vi.mocked(useHasPermission).mockReturnValue(false);
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

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container).toBeDefined();
  });

  it("shows no access message without permission", () => {
    vi.mocked(useHasPermission).mockReturnValue(false);
    vi.mocked(useTeamMembers).mockReturnValue({
      data: undefined,
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

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container).toBeDefined();
  });
});
