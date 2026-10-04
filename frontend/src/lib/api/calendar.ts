"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";
import { slaKeys } from "./sla";

export type BusinessCalendar = components["schemas"]["BusinessCalendarView"];
export type Holiday = components["schemas"]["HolidayView"];
export type SlaPolicy = components["schemas"]["SlaPolicyView"];
export type UpdateBusinessCalendarRequest = components["schemas"]["UpdateBusinessCalendarRequest"];
export type CreateHolidayRequest = components["schemas"]["CreateHolidayRequest"];
export type UpdateSlaPolicyRequest = components["schemas"]["UpdateSlaPolicyRequest"];

export const calendarKeys = {
  all: ["calendar"] as const,
  calendar: () => [...calendarKeys.all, "calendar"] as const,
  policy: () => [...calendarKeys.all, "policy"] as const,
};

export function useBusinessCalendar(enabled = true) {
  return useQuery({
    queryKey: calendarKeys.calendar(),
    queryFn: () => apiFetch<BusinessCalendar>("/admin/business-calendar"),
    enabled,
  });
}

export function useSlaPolicy(enabled = true) {
  return useQuery({
    queryKey: calendarKeys.policy(),
    queryFn: () => apiFetch<SlaPolicy>("/admin/sla-policy"),
    enabled,
  });
}

/** Calendar edits move every due date, so every clock and the exceptions board are stale too. */
function useCalendarInvalidation() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: calendarKeys.calendar() });
    void queryClient.invalidateQueries({ queryKey: slaKeys.all });
  };
}

export function useUpdateBusinessCalendar() {
  const invalidate = useCalendarInvalidation();
  return useMutation({
    mutationFn: (body: UpdateBusinessCalendarRequest) =>
      apiFetch<BusinessCalendar>("/admin/business-calendar", { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

export function useAddHoliday() {
  const invalidate = useCalendarInvalidation();
  return useMutation({
    mutationFn: (body: CreateHolidayRequest) =>
      apiFetch<Holiday>("/admin/business-calendar/holidays", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: invalidate,
  });
}

/** A POST to `/remove`, not a DELETE: business records are never deleted at the database layer. */
export function useRemoveHoliday() {
  const invalidate = useCalendarInvalidation();
  return useMutation({
    mutationFn: (id: string) => apiFetch<void>(`/admin/business-calendar/holidays/${id}/remove`, { method: "POST" }),
    onSuccess: invalidate,
  });
}

export function useUpdateSlaPolicy() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateSlaPolicyRequest) =>
      apiFetch<SlaPolicy>("/admin/sla-policy", { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: calendarKeys.policy() });
      void queryClient.invalidateQueries({ queryKey: slaKeys.all });
    },
  });
}
