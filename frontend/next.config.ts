import type { NextConfig } from "next";

import { apiRewrites } from "./src/lib/api/rewrites";

const nextConfig: NextConfig = {
  /**
   * The frontend talks to the backend same-origin; this is what makes that true.
   * See src/lib/api/rewrites.ts for why it is a rewrite and not a route handler,
   * and for the BACKEND_ORIGIN environment variable.
   */
  async rewrites() {
    return apiRewrites();
  },
};

export default nextConfig;
