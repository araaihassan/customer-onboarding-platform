"use client";

import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

export type NotificationItem = components["schemas"]["NotificationView"];
export type InboxPage = components["schemas"]["InboxPage"];
export type Preferences = components["schemas"]["PreferencesView"];
export type TypePreference = components["schemas"]["TypePreferenceView"];
export type UpdatePreferencesRequest = components["schemas"]["UpdatePreferencesRequest"];
export type NotificationPolicy = components["schemas"]["PolicyView"];
export type UpdateNotificationPolicyRequest = components["schemas"]["UpdatePolicyRequest"];
export type NotificationTemplate = components["schemas"]["TemplateView"];
export type CreateNotificationTemplateRequest = components["schemas"]["CreateNotificationTemplateRequest"];
export type UpdateNotificationTemplateRequest = components["schemas"]["UpdateTemplateRequest"];
export type TemplateOption = components["schemas"]["TemplateOption"];

export const notificationKeys = {
  all: ["notifications"] as const,
  count: () => [...notificationKeys.all, "count"] as const,
  inbox: () => [...notificationKeys.all, "inbox"] as const,
  preferences: () => [...notificationKeys.all, "preferences"] as const,
  policy: () => [...notificationKeys.all, "policy"] as const,
  templates: () => [...notificationKeys.all, "templates"] as const,
  templateOptions: () => [...notificationKeys.all, "template-options"] as const,
};

/** The badge. Real-time push is sub-project 8; until then it polls once a minute and on focus. */
export function useUnreadCount(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.count(),
    queryFn: async () => (await apiFetch<{ unreadCount: number }>("/notifications/unread-count")).unreadCount,
    enabled,
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useInbox(enabled: boolean) {
  return useInfiniteQuery({
    queryKey: notificationKeys.inbox(),
    queryFn: ({ pageParam }) =>
      apiFetch<InboxPage>(`/notifications?limit=30${pageParam ? `&cursor=${encodeURIComponent(pageParam)}` : ""}`),
    initialPageParam: null as string | null,
    getNextPageParam: (last) => last.nextCursor ?? null,
    enabled,
  });
}

function useInboxInvalidation() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: notificationKeys.count() });
    void queryClient.invalidateQueries({ queryKey: notificationKeys.inbox() });
  };
}

export function useMarkRead() {
  const invalidate = useInboxInvalidation();
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/notifications/${id}/read`, { method: "POST" }),
    onSettled: invalidate,
  });
}

export function useMarkAllRead() {
  const invalidate = useInboxInvalidation();
  return useMutation({
    mutationFn: () => apiFetch<{ marked: number }>("/notifications/read-all", { method: "POST" }),
    onSettled: invalidate,
  });
}

export function usePreferences(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.preferences(),
    queryFn: () => apiFetch<Preferences>("/notifications/preferences"),
    enabled,
  });
}

/** Optimistic: the toggle moves at once and snaps back if the server refuses (spec 9.3). */
export function useUpdatePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdatePreferencesRequest) =>
      apiFetch<Preferences>("/notifications/preferences", { method: "PUT", body: JSON.stringify(body) }),
    onMutate: async (body) => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.preferences() });
      const previous = queryClient.getQueryData<Preferences>(notificationKeys.preferences());
      if (previous) {
        queryClient.setQueryData<Preferences>(notificationKeys.preferences(), {
          emailCadence: body.emailCadence,
          types: (previous.types ?? []).map((tp) => {
            const next = body.types.find((b) => b.type === tp.type);
            return next ? { ...tp, inApp: next.inApp, email: next.email } : tp;
          }),
        });
      }
      return { previous };
    },
    onError: (_err, _body, context) => {
      if (context?.previous) queryClient.setQueryData(notificationKeys.preferences(), context.previous);
    },
    onSuccess: (data) => queryClient.setQueryData(notificationKeys.preferences(), data),
  });
}

export function useNotificationPolicy(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.policy(),
    queryFn: () => apiFetch<NotificationPolicy>("/admin/notification-policy"),
    enabled,
  });
}

export function useUpdateNotificationPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateNotificationPolicyRequest) =>
      apiFetch<NotificationPolicy>("/admin/notification-policy", { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: notificationKeys.policy() });
    },
  });
}

export function useNotificationTemplates(enabled = true) {
  return useQuery({
    queryKey: notificationKeys.templates(),
    queryFn: () => apiFetch<NotificationTemplate[]>("/admin/notification-templates"),
    enabled,
  });
}

/** A template edit changes both the admin list and the builder's picker of keys. */
function useTemplateInvalidation() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: notificationKeys.templates() });
    void queryClient.invalidateQueries({ queryKey: notificationKeys.templateOptions() });
  };
}

export function useCreateNotificationTemplate() {
  const invalidate = useTemplateInvalidation();
  return useMutation({
    mutationFn: (body: CreateNotificationTemplateRequest) =>
      apiFetch<NotificationTemplate>("/admin/notification-templates", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

export function useUpdateNotificationTemplate() {
  const invalidate = useTemplateInvalidation();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateNotificationTemplateRequest }) =>
      apiFetch<NotificationTemplate>(`/admin/notification-templates/${id}`, {
        method: "PUT",
        body: JSON.stringify(body),
      }),
    onSuccess: invalidate,
  });
}

export function useTemplateOptions(enabled: boolean) {
  return useQuery({
    queryKey: notificationKeys.templateOptions(),
    queryFn: () => apiFetch<TemplateOption[]>("/notification-templates/options"),
    enabled,
  });
}
