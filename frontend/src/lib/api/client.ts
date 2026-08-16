/**
 * The access token lives ONLY in this module-scoped variable — never
 * localStorage, never sessionStorage, never a cookie (spec §7.2).
 *
 * That is the entire point of the session model: an XSS that can read storage
 * would own the session, whereas a token held in a closure dies with the page. A
 * reload is survivable anyway, because the HttpOnly refresh cookie outlives the
 * token and AuthProvider trades it for a new one on mount.
 *
 * The temptation to "fix" a refresh bug by persisting the token is exactly what
 * client.test.ts guards against.
 */
let accessToken: string | null = null;
let tenantSlug = "";
let refreshInFlight: Promise<boolean> | null = null;

/** Test seams. Production code goes through AuthProvider. */
export function __setAccessToken(token: string | null) {
  accessToken = token;
}
export function __getAccessToken() {
  return accessToken;
}

export function setTenantSlug(slug: string) {
  tenantSlug = slug;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "ApiError";
  }
}

/**
 * Collapses concurrent refreshes into one in-flight promise.
 *
 * Not an optimisation. The backend treats a replayed refresh token as theft and
 * revokes the whole family (spec §7.4), so two parallel 401s each rotating would
 * log the user out everywhere and record a REFRESH_REUSE_DETECTED audit event —
 * from nothing worse than two requests racing.
 */
async function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;

  refreshInFlight = (async () => {
    const response = await fetch(`/api/t/${tenantSlug}/auth/refresh`, {
      method: "POST",
      credentials: "include",
    });

    if (!response.ok) {
      // Clear rather than keep: a token already known to be dead would otherwise
      // be retried on every later request, leaving a broken half-session.
      accessToken = null;
      return false;
    }

    const body = (await response.json()) as { accessToken: string };
    accessToken = body.accessToken;
    return true;
  })().finally(() => {
    refreshInFlight = null;
  });

  return refreshInFlight;
}

/**
 * Refreshes once on 401 and retries — once. Task 15 configures the backend to
 * answer 401 rather than Spring Security's default 403 for an expired token
 * precisely so this path triggers; a 403 would fall straight through to the error
 * branch and silently sign the user out.
 */
export async function apiFetch<T>(path: string, init: RequestInit = {}): Promise<T> {
  const send = () => {
    // Headers is normalised rather than spread: spreading a Headers instance
    // yields an empty object, so a caller passing one would silently lose its
    // headers — including Content-Type on a POST.
    const headers = new Headers(init.headers ?? {});
    if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
    if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);

    return fetch(`/api/t/${tenantSlug}${path}`, {
      ...init,
      credentials: "include", // carries the HttpOnly refresh cookie
      headers,
    });
  };

  let response = await send();

  if (response.status === 401) {
    const refreshed = await refreshAccessToken();
    if (refreshed) response = await send();
  }

  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }

  // 204 is the shape of every mutation that returns nothing — logout, activate,
  // deactivate, invite. Calling .json() on those would throw on an empty body.
  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}
