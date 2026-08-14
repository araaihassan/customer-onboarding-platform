import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ApiError, __setAccessToken, setTenantSlug } from "@/lib/api/client";
import {
  useCustomer,
  useCustomers,
  useContacts,
  useCreateCustomer,
  useDeactivateCustomer,
  useSendInvitation,
  useUpdateCustomer,
} from "./customers";

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function noContent() {
  return { ok: true, status: 204, text: async () => "", json: async () => undefined } as unknown as Response;
}

/**
 * A fresh client per test, with retries off. Retries would hide the one thing
 * these tests care about most — that a 404 arrives at the caller as a 404.
 */
function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function lastUrl(): string {
  return fetchMock.mock.calls.at(-1)![0] as string;
}

function lastInit(): RequestInit {
  return fetchMock.mock.calls.at(-1)![1] as RequestInit;
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

describe("useCustomers", () => {
  it("sends the search, status and page the caller asked for", async () => {
    fetchMock.mockResolvedValue(reply({ content: [], totalElements: 0, totalPages: 0, number: 0 }));

    const { result } = renderHook(() => useCustomers({ search: "north", status: "ACTIVE", page: 2 }), {
      wrapper: makeWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers?search=north&status=ACTIVE&page=2&size=25");
  });

  /**
   * An empty search box must not become `search=`. The backend treats a blank
   * search as no filter anyway, but an empty parameter makes every keystroke a
   * distinct cache key, so the list would refetch on the way back to "no filter".
   */
  it("omits filters that are not set", async () => {
    fetchMock.mockResolvedValue(reply({ content: [], totalElements: 0, totalPages: 0, number: 0 }));

    const { result } = renderHook(() => useCustomers({ search: "  ", status: null }), {
      wrapper: makeWrapper(),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers?page=0&size=25");
  });
});

describe("useCustomer", () => {
  it("fetches one customer by id", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1", displayName: "Northwind Foods" }));

    const { result } = renderHook(() => useCustomer("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers/c-1");
    expect(result.current.data?.displayName).toBe("Northwind Foods");
  });

  /**
   * The 404 must reach the caller intact. Out-of-scope and non-existent are
   * deliberately indistinguishable at the API, and the screen decides what to
   * render from the status alone.
   */
  it("surfaces a 404 as an ApiError with the status intact", async () => {
    fetchMock.mockResolvedValue(reply({ message: "not found" }, 404));

    const { result } = renderHook(() => useCustomer("missing"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isError).toBe(true));

    expect(result.current.error).toBeInstanceOf(ApiError);
    expect((result.current.error as ApiError).status).toBe(404);
  });
});

describe("useContacts", () => {
  it("reads the contacts nested under the customer", async () => {
    fetchMock.mockResolvedValue(reply([]));

    const { result } = renderHook(() => useContacts("c-1"), { wrapper: makeWrapper() });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));

    expect(lastUrl()).toBe("/api/t/acme/customers/c-1/contacts");
  });
});

describe("mutations", () => {
  it("creates a customer with POST", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-9" }, 201));

    const { result } = renderHook(() => useCreateCustomer(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ displayName: "Northwind", legalName: "Northwind Foods Ltd" });

    expect(lastUrl()).toBe("/api/t/acme/customers");
    expect(lastInit().method).toBe("POST");
    expect(JSON.parse(lastInit().body as string)).toMatchObject({ displayName: "Northwind" });
  });

  it("updates a customer with PUT", async () => {
    fetchMock.mockResolvedValue(reply({ id: "c-1" }));

    const { result } = renderHook(() => useUpdateCustomer(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ id: "c-1", body: { displayName: "Renamed" } });

    expect(lastUrl()).toBe("/api/t/acme/customers/c-1");
    expect(lastInit().method).toBe("PUT");
  });

  /**
   * Deactivation, never deletion. There is no DELETE endpoint and the database
   * would refuse one; this asserts the frontend never reaches for one.
   */
  it("deactivates with POST to the deactivate sub-resource", async () => {
    fetchMock.mockResolvedValue(noContent());

    const { result } = renderHook(() => useDeactivateCustomer(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ id: "c-1" });

    expect(lastUrl()).toBe("/api/t/acme/customers/c-1/deactivate");
    expect(lastInit().method).toBe("POST");
  });

  it("sends an invitation to a contact nested under its customer", async () => {
    fetchMock.mockResolvedValue(noContent());

    const { result } = renderHook(() => useSendInvitation(), { wrapper: makeWrapper() });
    await result.current.mutateAsync({ customerId: "c-1", contactId: "p-1" });

    expect(lastUrl()).toBe("/api/t/acme/customers/c-1/contacts/p-1/invitations");
    expect(lastInit().method).toBe("POST");
  });
});
