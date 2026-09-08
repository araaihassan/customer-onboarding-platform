"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys } from "./cases";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/cases.ts.
 */
export type Task = components["schemas"]["TaskView"];
export type CreateTaskRequest = components["schemas"]["CreateTaskRequest"];
export type TaskStatusRequest = components["schemas"]["TaskStatusRequest"];
export type TaskStatus = NonNullable<Task["status"]>;
export type TaskPriority = NonNullable<Task["priority"]>;

/** TaskController's own bucket vocabulary for the "My work" board (myWork's 400 doc comment names all four). */
export type MyWorkBucket = "do_now" | "in_progress" | "waiting" | "done_this_week";

export interface MyWorkFilters {
  bucket?: MyWorkBucket;
}

export const taskKeys = {
  all: ["tasks"] as const,
  forCase: (caseId: string) => [...taskKeys.all, "case", caseId] as const,
  mine: (filters: MyWorkFilters) => [...taskKeys.all, "mine", filters] as const,
};

/** Every task under one case -- the Tasks tab's own source. */
export function useCaseTasks(caseId: string) {
  return useQuery({
    queryKey: taskKeys.forCase(caseId),
    queryFn: () => apiFetch<Task[]>(`/cases/${caseId}/tasks`),
    enabled: Boolean(caseId),
  });
}

/**
 * The cross-case "My work" board -- always the caller's own tasks
 * (assignee=me is the only value myWork accepts; the endpoint 400s on
 * anything else), optionally narrowed to one bucket.
 */
export function useMyWork(filters: MyWorkFilters = {}) {
  const params = new URLSearchParams({ assignee: "me" });
  if (filters.bucket) params.set("bucket", filters.bucket);

  return useQuery({
    queryKey: taskKeys.mine(filters),
    queryFn: () => apiFetch<Task[]>(`/tasks?${params.toString()}`),
  });
}

/** Ad-hoc creation under a case -- the requirement-instantiated path has no UI trigger of its own. */
export function useCreateTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, ...body }: { caseId: string } & CreateTaskRequest) =>
      apiFetch<Task>(`/cases/${caseId}/tasks`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (_created, { caseId }) => {
      void queryClient.invalidateQueries({ queryKey: taskKeys.forCase(caseId) });
    },
  });
}

/**
 * Invalidates both the case's task list AND its roadmap: a requirement-linked
 * task completing can advance or complete a milestone, so the roadmap read
 * must not go stale the way it would if only the task list refreshed.
 */
export function useChangeTaskStatus() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, ...body }: { taskId: string } & TaskStatusRequest) =>
      apiFetch<Task>(`/tasks/${taskId}/status`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (updated) => {
      if (!updated.caseId) return;
      void queryClient.invalidateQueries({ queryKey: taskKeys.forCase(updated.caseId) });
      void queryClient.invalidateQueries({ queryKey: caseKeys.roadmap(updated.caseId) });
    },
  });
}
