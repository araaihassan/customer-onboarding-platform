"use client";

import { useQuery, type QueryClient } from "@tanstack/react-query";
import { ApiError, apiFetch } from "./client";
import type { components } from "./generated";

export type SlaClock = components["schemas"]["SlaClockView"];
export type SlaClockState = NonNullable<SlaClock["state"]>;
export type Exceptions = components["schemas"]["ExceptionsView"];
export type ExceptionCard = components["schemas"]["Card"];
export type EscalationNote = components["schemas"]["EscalationNote"];

export const slaKeys = {
  all: ["sla"] as const,
  clock: (caseId: string) => [...slaKeys.all, "clock", caseId] as const,
  exceptions: () => [...slaKeys.all, "exceptions"] as const,
};

/** The clock of the case's current stage; `null` when the stage has none (a normal state, not an error). */
export function useCaseSlaClock(caseId: string) {
  return useQuery({
    queryKey: slaKeys.clock(caseId),
    queryFn: async (): Promise<SlaClock | null> => {
      try {
        return await apiFetch<SlaClock>(`/cases/${caseId}/sla-clock`);
      } catch (e) {
        // Out-of-scope and no-SLA both read 404 (spec section 8); neither is an error to show.
        if (e instanceof ApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: Boolean(caseId),
  });
}

/**
 * A case's clock (and the war-room feed built from clocks) goes stale whenever a mutation can
 * change the stage or open/close a pause. The case page does not poll, so every such hook calls this.
 */
export function invalidateSla(queryClient: QueryClient, caseId: string) {
  void queryClient.invalidateQueries({ queryKey: slaKeys.clock(caseId) });
  void queryClient.invalidateQueries({ queryKey: slaKeys.exceptions() });
}

/** The tenant-wide exceptions board, refreshed every minute. */
export function useSlaExceptions() {
  return useQuery({
    queryKey: slaKeys.exceptions(),
    queryFn: () => apiFetch<Exceptions>("/sla/exceptions"),
    refetchInterval: 60_000,
  });
}
