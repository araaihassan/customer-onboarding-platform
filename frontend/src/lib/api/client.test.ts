import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, setTenantSlug, __setAccessToken, __getAccessToken } from "./client";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("apiFetch", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    setTenantSlug("acme");
    __setAccessToken("initial-token");
  });

  it("attaches the bearer token", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    // noUncheckedIndexedAccess makes the index access possibly-undefined, which is
    // the point of the flag: assert the call happened before reading it.
    const firstCall = fetchSpy.mock.calls[0];
    expect(firstCall).toBeDefined();
    const headers = new Headers((firstCall![1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBe("Bearer initial-token");
  });

  it("prefixes the tenant path", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    const firstCall = fetchSpy.mock.calls[0];
    expect(firstCall).toBeDefined();
    expect(firstCall![0]).toBe("/api/t/acme/customers");
  });

  it("refreshes once on 401 and retries the original request", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(json({ accessToken: "fresh-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(__getAccessToken()).toBe("fresh-token");
  });

  it("does not retry more than once", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("", { status: 401 }));
    vi.stubGlobal("fetch", fetchSpy);

    await expect(apiFetch("/customers")).rejects.toThrow();
    expect(fetchSpy.mock.calls.length).toBeLessThanOrEqual(3);
  });

  /**
   * Not in the plan, and the consequence of getting it wrong is severe rather than
   * merely wasteful. The backend treats a replayed refresh token as theft and
   * revokes the entire family (Task 16), so two parallel 401s each rotating would
   * log the user out of every session and write a REFRESH_REUSE_DETECTED audit
   * event — for what is really just two requests racing.
   */
  it("collapses concurrent refreshes into one", async () => {
    const fetchSpy = vi.fn(async (url: string) => {
      if (url.endsWith("/auth/refresh")) {
        return json({ accessToken: "fresh-token", expiresInSeconds: 900 });
      }
      // Every data request 401s until the token is refreshed.
      return __getAccessToken() === "fresh-token"
        ? json({ ok: true })
        : new Response("", { status: 401 });
    });
    vi.stubGlobal("fetch", fetchSpy);

    await Promise.all([apiFetch("/customers"), apiFetch("/admin/users"), apiFetch("/me")]);

    const refreshCalls = fetchSpy.mock.calls.filter(([url]) =>
      String(url).endsWith("/auth/refresh"),
    );
    expect(refreshCalls).toHaveLength(1);
  });

  /**
   * The test that matters. It is easy to "fix" a refresh bug by persisting the
   * token, which silently discards the entire reason for choosing this session
   * model — an XSS that can read localStorage would then own the session rather
   * than only the current page.
   *
   * The plan's version only spied on the setter. This exercises the whole flow,
   * including a refresh, and watches every persistence route a browser offers.
   */
  it("never writes the token to browser storage or a cookie", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    const cookieSetter = vi.fn();
    Object.defineProperty(document, "cookie", { configurable: true, set: cookieSetter, get: () => "" });

    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(json({ accessToken: "secret-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetch("/customers");

    expect(__getAccessToken()).toBe("secret-token");
    expect(setItem).not.toHaveBeenCalled();
    expect(cookieSetter).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  /**
   * Not in the plan. A failed refresh must clear the stale token rather than leave
   * it in place, or every later request retries with a credential already known to
   * be dead and the user sits in a broken half-session.
   */
  it("clears the token when the refresh itself is rejected", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("", { status: 401 }));
    vi.stubGlobal("fetch", fetchSpy);

    await expect(apiFetch("/customers")).rejects.toThrow();
    expect(__getAccessToken()).toBeNull();
  });
});
