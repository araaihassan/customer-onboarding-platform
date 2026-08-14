/**
 * Where the browser's same-origin `/api/…` requests actually go.
 *
 * `client.ts` issues `/api/t/{slug}/…` same-origin deliberately, so the HttpOnly
 * `refresh_token` cookie — `SameSite=Strict`, path-scoped to `/api/t/{slug}/auth`
 * — stays same-origin and is sent at all. A cross-origin `fetch` to :8080 would
 * drop it and silent refresh would never work.
 *
 * A `next.config.ts` rewrite is the framework-native way to honour that with no
 * runtime code of our own. The alternative, a catch-all route handler under
 * `app/api/…`, would have to forward cookies, headers, status codes and streaming
 * bodies correctly; one briefly existed as a mock and was correctly deleted.
 *
 * Rewrites are a development and runtime-server mechanism. A production
 * deployment behind a real reverse proxy configures the same mapping at the edge
 * instead — sub-project 10's packaging concern.
 *
 * Lives here rather than inline in `next.config.ts` so it can be tested; the
 * config file itself is not matched by the vitest `include` glob.
 */

/** The backend's default local address, so a fresh checkout runs unconfigured. */
export const DEV_BACKEND_ORIGIN = "http://localhost:8080";

type Env = Record<string, string | undefined>;

/**
 * Not `NEXT_PUBLIC_`: rewrites are resolved by the Next.js server, and prefixing
 * it would inline the backend's address into the client bundle for no reason.
 */
export function backendOrigin(env: Env = process.env): string {
  const configured = env.BACKEND_ORIGIN?.trim();
  // A trailing slash would double against the rewrite's leading one and produce
  // //api/…, which some proxies treat as a different path.
  return (configured || DEV_BACKEND_ORIGIN).replace(/\/+$/, "");
}

export function apiRewrites(env: Env = process.env) {
  return [
    {
      // Scoped to /api, and only /api. Next resolves filesystem routes before
      // these rewrites, so a real route handler under app/api would still win.
      source: "/api/:path*",
      destination: `${backendOrigin(env)}/api/:path*`,
    },
  ];
}
