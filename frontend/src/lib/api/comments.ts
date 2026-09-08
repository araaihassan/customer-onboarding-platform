"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name -- same discipline as lib/api/cases.ts.
 */
export type Comment = components["schemas"]["CommentView"];
export type CreateCommentRequest = components["schemas"]["CreateCommentRequest"];
export type UpdateCommentRequest = components["schemas"]["UpdateCommentRequest"];
export type CommentResourceType = NonNullable<Comment["resourceType"]>;

export const commentKeys = {
  all: ["comments"] as const,
  forResource: (caseId: string, resourceType: CommentResourceType, resourceId: string) =>
    [...commentKeys.all, caseId, resourceType, resourceId] as const,
};

/**
 * Every comment on one resource (a task or the case itself) -- gated
 * case.view, same as CommentService's own doc comment: there is no bespoke
 * comment.view permission.
 */
export function useComments(caseId: string, resourceType: CommentResourceType, resourceId: string) {
  return useQuery({
    queryKey: commentKeys.forResource(caseId, resourceType, resourceId),
    queryFn: () =>
      apiFetch<Comment[]>(`/cases/${caseId}/comments?resourceType=${resourceType}&resourceId=${resourceId}`),
    enabled: Boolean(caseId && resourceType && resourceId),
  });
}

export function useAddComment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ caseId, ...body }: { caseId: string } & CreateCommentRequest) =>
      apiFetch<Comment>(`/cases/${caseId}/comments`, { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (_created, { caseId, resourceType, resourceId }) => {
      void queryClient.invalidateQueries({ queryKey: commentKeys.forResource(caseId, resourceType, resourceId) });
    },
  });
}

/**
 * Task 26 built only `useComments`/`useAddComment` -- no edit hook, though
 * the backend's `PUT /comments/{commentId}` (`CommentService.update`,
 * reviewed in Task 24) has always been there: author-only, independent of
 * scope, per that service's own doc comment. Follows `useAddComment`'s exact
 * shape. `caseId`/`resourceType`/`resourceId` for the cache key come off the
 * response the same way `useToggleChecklistItem` reads `updated.taskId` --
 * the caller only ever has a commentId and a new body, not the resource
 * triple, so invalidation keys off what the server actually returns.
 */
export function useUpdateComment() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ commentId, ...body }: { commentId: string } & UpdateCommentRequest) =>
      apiFetch<Comment>(`/comments/${commentId}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: (updated) => {
      if (!updated.caseId || !updated.resourceType || !updated.resourceId) return;
      void queryClient.invalidateQueries({
        queryKey: commentKeys.forResource(updated.caseId, updated.resourceType, updated.resourceId),
      });
    },
  });
}
