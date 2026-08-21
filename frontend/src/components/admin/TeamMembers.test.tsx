import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, screen } from "@testing-library/react";
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

  const mockUsers = [
    { id: "user-3", fullName: "Charlie", email: "charlie@example.com" },
  ];

  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  const createWrapper = () => {
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
      data: { content: mockUsers },
      isLoading: false,
    } as any);

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    expect(container.textContent).toContain("admin.team.members.empty");
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
      data: { content: mockUsers },
      isLoading: false,
    } as any);

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });

    expect(container.textContent).toContain("Alice");
    expect(container.textContent).toContain("Bob");
  });

  it("renders component without error with permission to manage", () => {
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
      data: { content: mockUsers },
      isLoading: false,
    } as any);

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container).toBeDefined();
  });

  it("renders without add button when lacking permission", () => {
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
      data: { content: mockUsers },
      isLoading: false,
    } as any);

    const { container } = render(<TeamMembers team={mockTeam} />, { wrapper: createWrapper() });
    expect(container.textContent).toContain("admin.org.noAccess");
  });
});
