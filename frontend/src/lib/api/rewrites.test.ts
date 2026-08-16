import { describe, expect, it } from "vitest";

import { DEV_BACKEND_ORIGIN, apiRewrites, backendOrigin } from "./rewrites";

describe("backendOrigin", () => {
  it("defaults to the local backend so a checkout runs with no configuration", () => {
    expect(backendOrigin({})).toBe(DEV_BACKEND_ORIGIN);
    expect(DEV_BACKEND_ORIGIN).toBe("http://localhost:8080");
  });

  it("honours BACKEND_ORIGIN", () => {
    expect(backendOrigin({ BACKEND_ORIGIN: "http://api.internal:9000" })).toBe(
      "http://api.internal:9000",
    );
  });

  it("treats a blank value as unset rather than as an empty origin", () => {
    expect(backendOrigin({ BACKEND_ORIGIN: "   " })).toBe(DEV_BACKEND_ORIGIN);
  });

  it("strips a trailing slash, which would otherwise double in every destination", () => {
    expect(backendOrigin({ BACKEND_ORIGIN: "http://localhost:8080/" })).toBe(
      "http://localhost:8080",
    );
  });
});

describe("apiRewrites", () => {
  /**
   * client.ts issues same-origin /api/t/{slug}/… requests on purpose, so the
   * HttpOnly refresh cookie stays same-origin. Without this rewrite a browser at
   * :3000 never reaches :8080 and no page that calls the API works at all.
   */
  it("proxies the paths the API client actually issues", () => {
    const rule = apiRewrites({})[0]!;

    expect(rule.source).toBe("/api/:path*");
    expect(rule.destination).toBe("http://localhost:8080/api/:path*");
  });

  it("carries the :path* placeholder through to the destination unexpanded", () => {
    const rule = apiRewrites({ BACKEND_ORIGIN: "http://api.internal:9000" })[0]!;

    expect(rule.destination).toBe("http://api.internal:9000/api/:path*");
  });

  /**
   * Exactly one rule, scoped to /api. Anything broader would put the Next.js
   * application's own routes behind the proxy.
   */
  it("rewrites nothing outside /api", () => {
    expect(apiRewrites({})).toHaveLength(1);
  });
});
