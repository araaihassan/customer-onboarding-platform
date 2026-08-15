"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

/**
 * The `/admin/*` surface, in the shape `customers.ts` established.
 *
 * Not in Task 28's file list, and added for the same reason `PageHeader.tsx` was:
 * three screens call these endpoints, and a fetch written three times is a fetch
 * written wrong twice. Every type is the generated OpenAPI type re-exported under
 * a shorter name — nothing here describes a request or response shape of its own,
 * so a backend change is a compile error rather than a runtime surprise.
 */
export type Permission = components["schemas"]["PermissionView"];
export type Role = components["schemas"]["RoleView"];
export type User = components["schemas"]["UserView"];
export type UserPage = components["schemas"]["PageUserView"];
export type Department = components["schemas"]["DepartmentView"];
export type Team = components["schemas"]["TeamView"];
export type CreateUserRequest = components["schemas"]["CreateUserRequest"];
export type RoleRequest = components["schemas"]["RoleRequest"];

/** The scope vocabulary, straight off the generated grants map. */
export type Scope = NonNullable<Role["grants"]>[string];
export type Grants = Record<string, Scope>;

export const USER_PAGE_SIZE = 25;

export const adminKeys = {
  all: ["admin"] as const,
  permissions: () => [...adminKeys.all, "permissions"] as const,
  roles: () => [...adminKeys.all, "roles"] as const,
  users: (search: string, page: number) =>
    [...adminKeys.all, "users", search.trim(), page] as const,
  departments: () => [...adminKeys.all, "departments"] as const,
  teams: () => [...adminKeys.all, "teams"] as const,
};

/**
 * The permission catalog.
 *
 * Identical for every tenant and for the life of a deployment — it is compiled
 * into the backend, not stored per tenant — so it is cached for the session
 * rather than refetched whenever the role editor regains focus.
 */
export function usePermissions(enabled = true) {
  return useQuery({
    queryKey: adminKeys.permissions(),
    queryFn: () => apiFetch<Permission[]>("/admin/permissions"),
    staleTime: Infinity,
    enabled,
  });
}

/**
 * `enabled` is the caller's throughout this module, for the reason `useContacts`
 * gives: a user without the permission must not fire the request at all, or the
 * network log fills with 403s for screens that render perfectly well.
 */
export function useRoles(enabled = true) {
  return useQuery({
    queryKey: adminKeys.roles(),
    queryFn: () => apiFetch<Role[]>("/admin/roles"),
    enabled,
  });
}

export function useUsers(search: string, page = 0, enabled = true) {
  return useQuery({
    queryKey: adminKeys.users(search, page),
    queryFn: () => {
      const query = new URLSearchParams();
      const trimmed = search.trim();
      if (trimmed) query.set("search", trimmed);
      query.set("page", String(page));
      query.set("size", String(USER_PAGE_SIZE));
      return apiFetch<UserPage>(`/admin/users?${query.toString()}`);
    },
    // The previous page stays on screen while the next one loads, so paging and
    // searching do not flash a skeleton over a list the user is reading.
    placeholderData: (previous) => previous,
    enabled,
  });
}

export function useDepartments(enabled = true) {
  return useQuery({
    queryKey: adminKeys.departments(),
    queryFn: () => apiFetch<Department[]>("/admin/departments"),
    enabled,
  });
}

export function useTeams(enabled = true) {
  return useQuery({
    queryKey: adminKeys.teams(),
    queryFn: () => apiFetch<Team[]>("/admin/teams"),
    enabled,
  });
}

/**
 * Creating a user IS the invitation: the account is created INVITED with no
 * password hash and an activation email goes out in the same transaction. There
 * is no separate "send invite" call, and an administrator can never mint a
 * usable credential for a colleague.
 */
export function useInviteUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUserRequest) =>
      apiFetch<User>("/admin/users", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...adminKeys.all, "users"] });
    },
  });
}

/** Deactivation, never deletion — there is no DELETE on a user and never will be. */
export function useDeactivateUser() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) =>
      apiFetch<void>(`/admin/users/${id}/deactivate`, { method: "POST" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...adminKeys.all, "users"] });
    },
  });
}

export function useAssignRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      apiFetch<void>(`/admin/users/${userId}/roles`, {
        method: "POST",
        body: JSON.stringify({ roleId }),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...adminKeys.all, "users"] });
    },
  });
}

export function useUnassignRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, roleId }: { userId: string; roleId: string }) =>
      apiFetch<void>(`/admin/users/${userId}/roles/${roleId}`, { method: "DELETE" }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [...adminKeys.all, "users"] });
    },
  });
}

export function useCreateRole() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RoleRequest) =>
      apiFetch<{ id: string }>("/admin/roles", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.roles() });
    },
  });
}

/**
 * A full replace of the role's grants, which is what the endpoint is: a
 * permission left out of the map is revoked, exactly as one sent with a
 * different scope is re-scoped. The editor therefore always sends the whole set.
 */
export function useUpdateGrants() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ roleId, grants }: { roleId: string; grants: Grants }) =>
      apiFetch<void>(`/admin/roles/${roleId}/grants`, {
        method: "PUT",
        body: JSON.stringify(grants),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.roles() });
      // A changed grant changes what every holder of the role can do, and the
      // user list renders role membership.
      void queryClient.invalidateQueries({ queryKey: [...adminKeys.all, "users"] });
    },
  });
}

export function useSetRoleEnabled() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ roleId, enabled }: { roleId: string; enabled: boolean }) =>
      apiFetch<void>(`/admin/roles/${roleId}/${enabled ? "enable" : "disable"}`, {
        method: "POST",
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.roles() });
    },
  });
}

export function useCreateDepartment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; description: string }) =>
      apiFetch<Department>("/admin/departments", {
        method: "POST",
        body: JSON.stringify(body),
      }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.departments() });
    },
  });
}

export function useCreateTeam() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; description: string; departmentId?: string }) =>
      apiFetch<Team>("/admin/teams", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: adminKeys.teams() });
    },
  });
}

/**
 * The catalog grouped by category, categories and permissions each in the order
 * the backend returned them — which is `PermissionCatalog`'s declaration order,
 * and that order is deliberate: tenant, identity, authz, customer, audit.
 * Re-sorting alphabetically here would scatter related permissions.
 */
export function groupByCategory(permissions: Permission[]): [string, Permission[]][] {
  const groups = new Map<string, Permission[]>();
  for (const permission of permissions) {
    const category = permission.category ?? "";
    const existing = groups.get(category);
    if (existing) existing.push(permission);
    else groups.set(category, [permission]);
  }
  return Array.from(groups.entries());
}
