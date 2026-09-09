"use client";

import { useMemo } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import { caseKeys, type Case } from "./cases";
import { customerKeys, type Customer } from "./customers";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/cases.ts.
 */
export type Task = components["schemas"]["TaskView"];
export type CreateTaskRequest = components["schemas"]["CreateTaskRequest"];
export type TaskStatusRequest = components["schemas"]["TaskStatusRequest"];
export type UpdateTaskRequest = components["schemas"]["UpdateTaskRequest"];
export type TaskStatus = NonNullable<Task["status"]>;
export type TaskPriority = NonNullable<Task["priority"]>;
export type ChecklistItem = components["schemas"]["ChecklistItemView"];
export type UpdateChecklistItemRequest = components["schemas"]["UpdateChecklistItemRequest"];

/**
 * A milestone as a task-edit/create picker option -- just enough to render
 * a select (id + display name). Shared here rather than declared once per
 * caller: `TasksTab`'s own "New task" dialog and `TaskEditDialog` (Task 6)
 * both need exactly this shape, derived the same way (flattened from a
 * case's roadmap), so one type keeps them from drifting apart.
 */
export interface MilestoneOption {
  id: string;
  name: string;
}

/** TaskController's own bucket vocabulary for the "My work" board (myWork's 400 doc comment names all four). */
export type MyWorkBucket = "do_now" | "in_progress" | "waiting" | "done_this_week";

export interface MyWorkFilters {
  bucket?: MyWorkBucket;
}

export const taskKeys = {
  all: ["tasks"] as const,
  forCase: (caseId: string) => [...taskKeys.all, "case", caseId] as const,
  mine: (filters: MyWorkFilters) => [...taskKeys.all, "mine", filters] as const,
  /**
   * The whole "My work" family, every filter combination at once -- a prefix
   * of every `taskKeys.mine(filters)` key. Used to invalidate the board
   * regardless of which bucket (or none) it currently has selected, since a
   * write that changes a task's status can move it into or out of any bucket.
   */
  mineAll: () => [...taskKeys.all, "mine"] as const,
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

export interface WorkItemContext {
  caseName?: string;
  customerName?: string;
}

/**
 * Resolves each task's case name and customer name for the cross-case "My
 * work" board (Task 28, SCREENS.md §5: "context, then a chip + customer
 * name"). `TaskView` carries only `caseId` -- Task 26 never had a reason to
 * denormalise a case or customer name onto it -- so this is a two-hop
 * lookup: case (for its own `name` and `customerId`), then customer (for
 * `displayName`). Both hops reuse `caseKeys`/`customerKeys` from
 * cases.ts/customers.ts, so a case or customer another screen already
 * fetched (the case workspace, the customer list) costs nothing extra here,
 * and this board's own fetches are reusable the same way in return.
 *
 * Deliberately tolerant of a still-loading or failed hop: a card is never
 * blocked on its context resolving, it just renders without the extra line
 * until the lookup completes. No defensive re-fetch or retry logic beyond
 * what `useQuery`'s defaults already give every other read in this file.
 */
export function useWorkContexts(tasks: Task[]): Map<string, WorkItemContext> {
  const caseIds = useMemo(
    () => Array.from(new Set(tasks.map((task) => task.caseId).filter((id): id is string => Boolean(id)))),
    [tasks],
  );

  const caseResults = useQueries({
    queries: caseIds.map((caseId) => ({
      queryKey: caseKeys.detail(caseId),
      queryFn: () => apiFetch<Case>(`/cases/${caseId}`),
    })),
  });

  const casesById = useMemo(() => {
    const map = new Map<string, Case>();
    caseIds.forEach((caseId, index) => {
      const data = caseResults[index]?.data;
      if (data) map.set(caseId, data);
    });
    return map;
  }, [caseIds, caseResults]);

  const customerIds = useMemo(
    () =>
      Array.from(
        new Set(Array.from(casesById.values()).map((c) => c.customerId).filter((id): id is string => Boolean(id))),
      ),
    [casesById],
  );

  const customerResults = useQueries({
    queries: customerIds.map((customerId) => ({
      queryKey: customerKeys.detail(customerId),
      queryFn: () => apiFetch<Customer>(`/customers/${customerId}`),
    })),
  });

  const customersById = useMemo(() => {
    const map = new Map<string, Customer>();
    customerIds.forEach((customerId, index) => {
      const data = customerResults[index]?.data;
      if (data) map.set(customerId, data);
    });
    return map;
  }, [customerIds, customerResults]);

  return useMemo(() => {
    const map = new Map<string, WorkItemContext>();
    for (const [caseId, caseData] of casesById) {
      const customerName = caseData.customerId ? customersById.get(caseData.customerId)?.displayName : undefined;
      map.set(caseId, { caseName: caseData.name, customerName });
    }
    return map;
  }, [casesById, customersById]);
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
 * Invalidates the case's task list, its roadmap, AND the whole "My work"
 * family: a requirement-linked task completing can advance or complete a
 * milestone, so the roadmap read must not go stale the way it would if only
 * the task list refreshed -- and a status change moves the task between
 * `useMyWork` buckets (Task 28's board), so every `taskKeys.mine(...)` query,
 * whichever filter it currently holds, must go stale too. Without this,
 * `/work` keeps showing a task in the column it just left until something
 * unrelated remounts the query -- `QueryProvider`'s `staleTime: 30_000` and
 * `refetchOnWindowFocus: false` mean that can persist for the rest of the
 * session on that screen, not just "up to 30 seconds."
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
      void queryClient.invalidateQueries({ queryKey: taskKeys.mineAll() });
    },
  });
}

/**
 * A full-replace edit -- title, description, priority, due date, milestone
 * (and assignee, round-tripped rather than editable here: Task 7 adds the
 * picker for that field). `UpdateTaskRequest` has no `status`/`requirementId`
 * field at all (its own doc comment says so; a status transition is
 * `useChangeTaskStatus`'s job, never this one's), so there is nothing here to
 * accidentally overwrite on that front.
 *
 * Invalidates the same two families `useChangeTaskStatus` does -- the case's
 * task list (so `TasksTab`'s grouping and the open detail panel both see the
 * edit) and the whole "My work" family (title/priority/due date all render on
 * that board's cards too). Not the roadmap: none of these fields feeds
 * progress or requirement satisfaction, unlike a status change.
 */
export function useUpdateTask() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ taskId, body }: { taskId: string; body: UpdateTaskRequest }) =>
      apiFetch<Task>(`/tasks/${taskId}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: (updated) => {
      if (!updated.caseId) return;
      void queryClient.invalidateQueries({ queryKey: taskKeys.forCase(updated.caseId) });
      void queryClient.invalidateQueries({ queryKey: taskKeys.mineAll() });
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
