"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { components } from "./generated";

/**
 * Every type here is the generated OpenAPI type, re-exported under a shorter
 * name. Nothing in this file describes a request or response shape of its own —
 * a backend change must be a compile error, not a runtime surprise.
 */
export type Customer = components["schemas"]["CustomerView"];
export type Contact = components["schemas"]["ContactView"];
export type CustomerPage = components["schemas"]["PageCustomerView"];
export type CreateCustomerRequest = components["schemas"]["CreateCustomerRequest"];
export type UpdateCustomerRequest = components["schemas"]["UpdateCustomerRequest"];
export type CustomerStatus = NonNullable<Customer["status"]>;

/** The four statuses, in lifecycle order — this drives the filter chips. */
export const CUSTOMER_STATUSES: readonly CustomerStatus[] = [
  "PROSPECT",
  "ACTIVE",
  "ON_HOLD",
  "INACTIVE",
];

export const CUSTOMER_PAGE_SIZE = 25;

export type CustomerListParams = {
  search?: string;
  status?: CustomerStatus | null;
  page?: number;
};

/**
 * Query keys in one place so a mutation can invalidate without guessing. The
 * list key carries the normalised params, not the raw ones — otherwise "" and
 * "  " would be two cache entries for the same result.
 */
export const customerKeys = {
  all: ["customers"] as const,
  lists: () => [...customerKeys.all, "list"] as const,
  list: (params: CustomerListParams) =>
    [...customerKeys.lists(), normalise(params)] as const,
  detail: (id: string) => [...customerKeys.all, "detail", id] as const,
  contacts: (customerId: string) => [...customerKeys.all, "contacts", customerId] as const,
};

type NormalisedParams = { search: string; status: CustomerStatus | ""; page: number };

function normalise(params: CustomerListParams): NormalisedParams {
  return {
    search: params.search?.trim() ?? "",
    status: params.status ?? "",
    page: params.page ?? 0,
  };
}

function listPath(params: CustomerListParams): string {
  const { search, status, page } = normalise(params);
  const query = new URLSearchParams();
  // Blank filters are omitted rather than sent empty: the backend treats a blank
  // search as no filter, but an empty parameter would still make every keystroke
  // on the way back to "no filter" a distinct URL and a distinct cache entry.
  if (search) query.set("search", search);
  if (status) query.set("status", status);
  query.set("page", String(page));
  query.set("size", String(CUSTOMER_PAGE_SIZE));
  return `/customers?${query.toString()}`;
}

export function useCustomers(params: CustomerListParams) {
  return useQuery({
    queryKey: customerKeys.list(params),
    queryFn: () => apiFetch<CustomerPage>(listPath(params)),
    // The previous page stays on screen while the next one loads, so paging and
    // filtering do not flash a skeleton over a list the user is reading.
    placeholderData: (previous) => previous,
  });
}

export function useCustomer(id: string) {
  return useQuery({
    queryKey: customerKeys.detail(id),
    queryFn: () => apiFetch<Customer>(`/customers/${id}`),
    enabled: Boolean(id),
  });
}

/**
 * `enabled` is the caller's, because a user without contact.view must not fire
 * the request at all — a 403 in the network log for a screen that renders fine
 * is noise that costs someone an afternoon later.
 */
export function useContacts(customerId: string, enabled = true) {
  return useQuery({
    queryKey: customerKeys.contacts(customerId),
    queryFn: () => apiFetch<Contact[]>(`/customers/${customerId}/contacts`),
    enabled: enabled && Boolean(customerId),
  });
}

export function useCreateCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateCustomerRequest) =>
      apiFetch<Customer>("/customers", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: customerKeys.lists() });
    },
  });
}

export function useUpdateCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateCustomerRequest }) =>
      apiFetch<Customer>(`/customers/${id}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: (customer, { id }) => {
      queryClient.setQueryData(customerKeys.detail(id), customer);
      void queryClient.invalidateQueries({ queryKey: customerKeys.lists() });
    },
  });
}

/**
 * Deactivation, never deletion. There is no DELETE endpoint, the database would
 * refuse one, and no delete affordance exists anywhere in the interface.
 */
export function useDeactivateCustomer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) =>
      apiFetch<void>(`/customers/${id}/deactivate`, {
        method: "POST",
        // The body is optional server-side, so it is sent only when there is
        // something to say. An empty JSON object would record an empty reason on
        // the audit event as though one had been given.
        ...(reason ? { body: JSON.stringify({ reason }) } : {}),
      }),
    onSuccess: (_result, { id }) => {
      void queryClient.invalidateQueries({ queryKey: customerKeys.detail(id) });
      void queryClient.invalidateQueries({ queryKey: customerKeys.lists() });
    },
  });
}

/**
 * 204 with no body by design: the raw activation token goes to the contact by
 * email and is never returned to the caller, who would otherwise hold a
 * credential for someone else's account.
 */
export function useSendInvitation() {
  return useMutation({
    mutationFn: ({ customerId, contactId }: { customerId: string; contactId: string }) =>
      apiFetch<void>(`/customers/${customerId}/contacts/${contactId}/invitations`, {
        method: "POST",
      }),
  });
}

/**
 * The last group of a UUID, as a quotable reference.
 *
 * Deliberately the tail, not the head. UUIDv7 encodes a millisecond timestamp in
 * its leading 48 bits, so the first eight characters are identical for every
 * record created within about a minute of each other — a prefix would look like
 * an identifier while identifying nothing.
 */
export function shortId(id: string | undefined): string {
  if (!id) return "";
  const parts = id.split("-");
  return parts[parts.length - 1] ?? id;
}
