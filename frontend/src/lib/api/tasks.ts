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
export type ChecklistItem = components["schemas"]["ChecklistItemView"];
export type UpdateChecklistItemRequest = components["schemas"]["UpdateChecklistItemRequest"];

/** TaskController's own bucket vocabulary for the "My work" board (myWork's 400 doc comment names all four). */
export type MyWorkBucket = "do_now" | "in_progress" | "waiting" | "done_this_week";

export interface MyWorkFilters {
  bucket?: MyWorkBucket;
}

export const taskKeys = {
  all: ["tasks"] as const,
  forCase: (caseId: string) => [...taskKeys.all, "case", caseId] as const,
  mine: (filters: MyWorkFilters) => [...taskKeys.all, "mine", filters] as const,
  checklist: (taskId: string) => [...taskKeys.all, "checklist", taskId] as const,
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

/**
 * A task's checklist -- Task 27's own addition, closing a gap Task 22/24 left:
 * `ChecklistService` exposed no way to LIST a task's items, only to add one
 * (returns a bare id) or update one by its own itemId (a side effect of a
 * write). `GET /tasks/{taskId}/checklist` was added in the same backend
 * change as this hook so `ChecklistEditor` has something to read on mount.
 */
export function useChecklist(taskId: string) {
  return useQuery({
    queryKey: taskKeys.checklist(taskId),
    queryFn: () => apiFetch<ChecklistItem[]>(`/tasks/${taskId}/checklist`),
    enabled: Boolean(taskId),
  });
}

/** Adds one checklist line -- the endpoint returns only the new item's id (ChecklistService.add's own doc comment), so the list is refetched rather than appended locally. */
export function useAddChecklistItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, label }: { taskId: string; label: string }) =>
      apiFetch<string>(`/tasks/${taskId}/checklist`, { method: "POST", body: JSON.stringify({ label }) }),
    onSuccess: (_id, { taskId }) => {
      void queryClient.invalidateQueries({ queryKey: taskKeys.checklist(taskId) });
    },
  });
}

/**
 * Flips one item's `done` -- and only that. Never calls `useChangeTaskStatus`:
 * ticking every item does not complete the task (the backend rule from Task
 * 22, asserted again here so the two cannot drift -- see `ChecklistEditor`'s
 * own doc comment). The response already carries `taskId`, so the caller
 * needs to pass nothing beyond the item id itself.
 */
export function useToggleChecklistItem() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (itemId: string) =>
      apiFetch<ChecklistItem>(`/checklist/${itemId}`, { method: "PUT", body: JSON.stringify({ toggleDone: true }) }),
    onSuccess: (updated) => {
      if (!updated.taskId) return;
      void queryClient.invalidateQueries({ queryKey: taskKeys.checklist(updated.taskId) });
    },
  });
}
