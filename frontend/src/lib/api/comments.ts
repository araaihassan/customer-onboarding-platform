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
