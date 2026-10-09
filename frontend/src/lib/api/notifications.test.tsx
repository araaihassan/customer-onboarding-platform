import type { ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { __setAccessToken, setTenantSlug } from "@/lib/api/client";
import {
  notificationKeys,
  useCreateNotificationTemplate,
  useInbox,
  useMarkAllRead,
  useMarkRead,
  useNotificationPolicy,
  useNotificationTemplates,
  usePreferences,
  useTemplateOptions,
  useUnreadCount,
  useUpdateNotificationPolicy,
  useUpdateNotificationTemplate,
  useUpdatePreferences,
  type Preferences,
} from "./notifications";

const fetchMock = vi.fn();

function reply(body: unknown, status = 200) {
  return {
    ok: status < 400,
    status,
    text: async () => JSON.stringify(body),
    json: async () => body,
  } as unknown as Response;
}

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const spy = vi.spyOn(client, "invalidateQueries");
  const Wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return { client, spy, Wrapper };
}

function invalidated(spy: { mock: { calls: unknown[][] } }): string[] {
  return spy.mock.calls.map((c) => JSON.stringify((c[0] as { queryKey: unknown }).queryKey));
}

function init(i: number): RequestInit {
  return fetchMock.mock.calls[i]![1] as RequestInit;
}

beforeEach(() => {
  fetchMock.mockReset();
  global.fetch = fetchMock as unknown as typeof fetch;
  setTenantSlug("acme");
  __setAccessToken("token");
});

afterEach(cleanup);

describe("notification reads", () => {
  it("useUnreadCount GETs the count and returns the number", async () => {
    fetchMock.mockResolvedValue(reply({ unreadCount: 7 }));
    const { Wrapper } = setup();
    const { result } = renderHook(() => useUnreadCount(true), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBe(7);
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/notifications/unread-count");
  });

  it("useUnreadCount is disabled when enabled is false", async () => {
    const { Wrapper } = setup();
    const { result } = renderHook(() => useUnreadCount(false), { wrapper: Wrapper });
    await new Promise((r) => setTimeout(r, 20));
    expect(fetchMock).not.toHaveBeenCalled();
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("useInbox passes the previous page's cursor and stops when it is null", async () => {
    fetchMock
      .mockResolvedValueOnce(reply({ items: [{ id: "a" }], unreadCount: 2, nextCursor: "c1" }))
      .mockResolvedValueOnce(reply({ items: [{ id: "b" }], unreadCount: 2, nextCursor: null }));
    const { Wrapper } = setup();
    const { result } = renderHook(() => useInbox(true), { wrapper: Wrapper });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/notifications?limit=30");
    expect(result.current.hasNextPage).toBe(true);
    await act(async () => {
      await result.current.fetchNextPage();
    });
    expect(fetchMock.mock.calls[1]![0]).toBe("/api/t/acme/notifications?limit=30&cursor=c1");
    await waitFor(() => expect(result.current.hasNextPage).toBe(false));
    expect(result.current.data?.pages).toHaveLength(2);
  });

  it("usePreferences, policy, templates and options read their routes", async () => {
    fetchMock.mockResolvedValue(reply([]));
    const { Wrapper } = setup();
    const a = renderHook(() => usePreferences(true), { wrapper: Wrapper });
    const b = renderHook(() => useNotificationPolicy(), { wrapper: Wrapper });
    const c = renderHook(() => useNotificationTemplates(), { wrapper: Wrapper });
    const d = renderHook(() => useTemplateOptions(true), { wrapper: Wrapper });
    await waitFor(() =>
      expect([a, b, c, d].every((h) => h.result.current.isSuccess)).toBe(true),
    );
    const urls = fetchMock.mock.calls.map((x) => x[0]);
    expect(urls).toContain("/api/t/acme/notifications/preferences");
    expect(urls).toContain("/api/t/acme/admin/notification-policy");
    expect(urls).toContain("/api/t/acme/admin/notification-templates");
    expect(urls).toContain("/api/t/acme/notification-templates/options");
  });
});

describe("mark read", () => {
  it("useMarkRead POSTs the id and invalidates count and inbox", async () => {
    fetchMock.mockResolvedValue(reply(undefined, 204));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useMarkRead(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync("n1");
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/notifications/n1/read");
    expect(init(0).method).toBe("POST");
    await waitFor(() => {
      expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.count()));
      expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.inbox()));
    });
  });

  it("useMarkAllRead POSTs read-all and invalidates count and inbox", async () => {
    fetchMock.mockResolvedValue(reply({ marked: 3 }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useMarkAllRead(), { wrapper: Wrapper });
    let out: { marked?: number } | undefined;
    await act(async () => {
      out = await result.current.mutateAsync();
    });
    expect(out).toEqual({ marked: 3 });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/notifications/read-all");
    expect(init(0).method).toBe("POST");
    await waitFor(() => {
      expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.count()));
      expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.inbox()));
    });
  });
});

describe("useUpdatePreferences", () => {
  const before: Preferences = {
    emailCadence: "IMMEDIATE",
    types: [
      { type: "TASK_ASSIGNED", label: "Task assigned", inApp: true, email: true },
      { type: "NEW_COMMENT", label: "New comment", inApp: true, email: false },
    ],
  };
  const body = {
    emailCadence: "DAILY" as const,
    types: [
      { type: "TASK_ASSIGNED" as const, inApp: false, email: true },
      { type: "NEW_COMMENT" as const, inApp: true, email: false },
    ],
  };

  it("PUTs the full body and applies it to the cache before the response", async () => {
    let release!: (r: Response) => void;
    fetchMock.mockReturnValue(new Promise<Response>((res) => (release = res)));
    const { client, Wrapper } = setup();
    client.setQueryData(notificationKeys.preferences(), before);
    const { result } = renderHook(() => useUpdatePreferences(), { wrapper: Wrapper });
    act(() => {
      result.current.mutate(body);
    });
    await waitFor(() => {
      const cached = client.getQueryData<Preferences>(notificationKeys.preferences());
      expect(cached?.emailCadence).toBe("DAILY");
      expect(cached?.types?.[0]?.inApp).toBe(false);
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/notifications/preferences");
    expect(init(0).method).toBe("PUT");
    expect(init(0).body).toBe(JSON.stringify(body));
    await act(async () => {
      release(reply({ ...before, emailCadence: "DAILY" }));
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("restores the previous value when the request fails", async () => {
    fetchMock.mockResolvedValue(reply({ message: "no" }, 500));
    const { client, Wrapper } = setup();
    client.setQueryData(notificationKeys.preferences(), before);
    const { result } = renderHook(() => useUpdatePreferences(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync(body).catch(() => undefined);
    });
    expect(client.getQueryData(notificationKeys.preferences())).toEqual(before);
  });
});

describe("admin hooks", () => {
  it("useUpdateNotificationPolicy PUTs the policy and invalidates it", async () => {
    fetchMock.mockResolvedValue(reply({ autoRemind: { enabled: true, intervalDays: 2, max: 3 }, horizons: {} }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateNotificationPolicy(), { wrapper: Wrapper });
    const policy = { autoRemind: { enabled: true, intervalDays: 2, max: 3 }, horizons: {} };
    await act(async () => {
      await result.current.mutateAsync(policy);
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/notification-policy");
    expect(init(0).method).toBe("PUT");
    expect(init(0).body).toBe(JSON.stringify(policy));
    expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.policy()));
  });

  it("useCreateNotificationTemplate POSTs and invalidates templates and options", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t1" }, 201));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useCreateNotificationTemplate(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({
        key: "k",
        name: "N",
        enteredSubject: "s",
        enteredBody: "b",
        active: true,
      });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/notification-templates");
    expect(init(0).method).toBe("POST");
    expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.templates()));
    expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.templateOptions()));
  });

  it("useUpdateNotificationTemplate PUTs by id and invalidates templates and options", async () => {
    fetchMock.mockResolvedValue(reply({ id: "t1" }));
    const { spy, Wrapper } = setup();
    const { result } = renderHook(() => useUpdateNotificationTemplate(), { wrapper: Wrapper });
    await act(async () => {
      await result.current.mutateAsync({
        id: "t1",
        body: { key: "k", name: "N", enteredSubject: "s", enteredBody: "b", active: false },
      });
    });
    expect(fetchMock.mock.calls[0]![0]).toBe("/api/t/acme/admin/notification-templates/t1");
    expect(init(0).method).toBe("PUT");
    expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.templates()));
    expect(invalidated(spy)).toContain(JSON.stringify(notificationKeys.templateOptions()));
  });
});
