import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiFetch, apiFetchBlob, setTenantSlug, __setAccessToken, __getAccessToken } from "./client";

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

  /**
   * Sub-project 4's own addition (Task 30): a document upload/version body is
   * FormData, never JSON. Setting a Content-Type ourselves -- even
   * "multipart/form-data" -- would strip the boundary parameter fetch adds
   * automatically, which is what actually delimits each part; the request
   * would then fail to parse server-side. Asserts absence rather than a
   * specific value, since the correct behaviour is "not present at all",
   * never "present with some particular string".
   */
  it("sends a FormData body with no Content-Type header at all", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    const body = new FormData();
    body.append("file", new Blob(["hello"]), "hello.txt");

    await apiFetch("/documents", { method: "POST", body });

    const firstCall = fetchSpy.mock.calls[0];
    expect(firstCall).toBeDefined();
    const headers = new Headers((firstCall![1] as RequestInit).headers);
    expect(headers.has("Content-Type")).toBe(false);
  });

  /** A caller-supplied Content-Type must not survive either -- there is nothing sensible to override it to on a multipart body. */
  it("strips a caller-supplied Content-Type from a FormData request", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    const body = new FormData();
    body.append("file", new Blob(["hello"]), "hello.txt");

    await apiFetch("/documents", {
      method: "POST",
      body,
      headers: { "Content-Type": "multipart/form-data" },
    });

    const firstCall = fetchSpy.mock.calls[0];
    expect(firstCall).toBeDefined();
    const headers = new Headers((firstCall![1] as RequestInit).headers);
    expect(headers.has("Content-Type")).toBe(false);
  });

  /**
   * The refresh-and-retry loop must apply unchanged to a multipart call --
   * the whole reason to extend apiFetch rather than have upload hooks call
   * fetch directly, which would silently lose refresh-on-401 for every
   * upload. Reuses the same refresh fixture as "refreshes once on 401 and
   * retries the original request" above, adapted for a FormData body.
   */
  it("refreshes once on 401 and retries a multipart request, still with no Content-Type", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(json({ accessToken: "fresh-token", expiresInSeconds: 900 }))
      .mockResolvedValueOnce(json({ ok: true }));
    vi.stubGlobal("fetch", fetchSpy);

    const body = new FormData();
    body.append("file", new Blob(["hello"]), "hello.txt");

    await apiFetch("/documents", { method: "POST", body });

    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(__getAccessToken()).toBe("fresh-token");

    const retryCall = fetchSpy.mock.calls[2];
    expect(retryCall).toBeDefined();
    const headers = new Headers((retryCall![1] as RequestInit).headers);
    expect(headers.has("Content-Type")).toBe(false);
    expect(headers.get("Authorization")).toBe("Bearer fresh-token");
  });
});

/**
 * Task 33, Ruling 4: `apiFetchBlob` must share `apiFetch`'s own auth-header
 * attachment and 401-refresh-and-retry logic exactly, not a second copy that
 * could drift -- these mirror `apiFetch`'s own "attaches the bearer token"
 * and "refreshes once on 401 and retries" cases above, against the shared
 * `sendWithRefresh` core, and add the one thing genuinely different: the
 * response is read as a Blob, never parsed as JSON.
 */
describe("apiFetchBlob", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    setTenantSlug("acme");
    __setAccessToken("initial-token");
  });

  it("attaches the bearer token and returns the response body as a Blob", async () => {
    // A plain string body, not a Blob passed to the Response constructor --
    // jsdom's own Response/Blob polyfill does not round-trip a Blob BODY
    // faithfully (it stringifies it to "[object Blob]" before wrapping),
    // which is an environment quirk of the test double, not of `.blob()`
    // itself; a string body exercises the exact same `apiFetchBlob` code
    // path without tripping it.
    const fetchSpy = vi.fn().mockResolvedValue(new Response("file bytes", { status: 200, headers: { "Content-Type": "application/pdf" } }));
    vi.stubGlobal("fetch", fetchSpy);

    const result = await apiFetchBlob("/documents/doc-1/versions/1/content");

    const firstCall = fetchSpy.mock.calls[0];
    expect(firstCall).toBeDefined();
    expect(firstCall![0]).toBe("/api/t/acme/documents/doc-1/versions/1/content");
    const headers = new Headers((firstCall![1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBe("Bearer initial-token");
    expect(result).toBeInstanceOf(Blob);
    expect(await result.text()).toBe("file bytes");
  });

  it("refreshes once on 401 and retries the original request, still returning a Blob", async () => {
    const fetchSpy = vi
      .fn()
      .mockResolvedValueOnce(new Response("", { status: 401 }))
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ accessToken: "fresh-token", expiresInSeconds: 900 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
      )
      .mockResolvedValueOnce(new Response("file bytes", { status: 200, headers: { "Content-Type": "application/pdf" } }));
    vi.stubGlobal("fetch", fetchSpy);

    const result = await apiFetchBlob("/documents/doc-1/versions/1/content");

    expect(fetchSpy).toHaveBeenCalledTimes(3);
    expect(__getAccessToken()).toBe("fresh-token");
    expect(await result.text()).toBe("file bytes");
  });

  it("throws ApiError on a non-ok response, never returning a Blob", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("not found", { status: 404 }));
    vi.stubGlobal("fetch", fetchSpy);

    await expect(apiFetchBlob("/documents/missing/versions/1/content")).rejects.toThrow();
  });

  /**
   * Production download code must go through `apiFetchBlob`, never reach
   * past it into the module's test-only token seams -- the brief's own
   * explicit requirement. This does not (and cannot) prove the seam is
   * unused elsewhere in the codebase; it proves `apiFetchBlob` itself does
   * not require a caller to touch `__getAccessToken`/`__setAccessToken` to
   * get an authenticated request, which is what makes going through them a
   * pure regression rather than ever necessary.
   */
  it("authenticates without the caller ever touching the token test seams", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("x", { status: 200 }));
    vi.stubGlobal("fetch", fetchSpy);

    await apiFetchBlob("/documents/doc-1/versions/1/content");

    const firstCall = fetchSpy.mock.calls[0];
    const headers = new Headers((firstCall![1] as RequestInit).headers);
    expect(headers.get("Authorization")).toBe("Bearer initial-token");
  });
});
