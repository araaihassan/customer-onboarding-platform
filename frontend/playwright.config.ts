import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end configuration.
 *
 * Both applications are started by Playwright rather than by hand, so
 * `npx playwright test` from a clean checkout is the whole command. The backend
 * goes through `e2e/support/backend.mjs`, which tees its output to a log file —
 * activation tokens are only readable there, and Playwright gives a test no way
 * to read a webServer's stdout.
 *
 * Everything is driven through :3000, never :8080 directly. The `next.config.ts`
 * rewrite is what makes the frontend's same-origin `/api/…` requests reach the
 * backend at all, and a suite that bypassed it would not have caught the run
 * where it was missing. It also keeps the HttpOnly `SameSite=Strict` refresh
 * cookie attached, which the refresh and reuse specs depend on entirely.
 */
export const BASE_URL = process.env.E2E_BASE_URL ?? "http://localhost:3000";

/** Matches the defaults in e2e/support/backend.mjs; the specs provision with it. */
export const PLATFORM_ADMIN = {
  email: process.env.APP_PLATFORM_ADMIN_EMAIL ?? "ops@example.com",
  password: process.env.APP_PLATFORM_ADMIN_PASSWORD ?? "e2e-platform-admin-password",
};

export default defineConfig({
  testDir: "./e2e",
  // Excludes the shared helpers, which are modules rather than specs.
  testMatch: "**/*.spec.ts",
  // Files in parallel, tests within a file in one worker. Each spec provisions a
  // tenant in `beforeAll`, and `beforeAll` runs once per worker — fully parallel
  // would provision one tenant per test and boot four browser contexts to do it.
  fullyParallel: false,
  // A run that quietly skips assertions because someone left `.only` in is worse
  // than a red build.
  forbidOnly: Boolean(process.env.CI),
  // No retries anywhere. A retry turns a real race into an intermittent pass, and
  // the flows here — silent refresh, rotation, reuse detection — are exactly the
  // ones where a race is the bug.
  retries: 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [["list"]],
  timeout: 60_000,
  expect: { timeout: 10_000 },

  use: {
    baseURL: BASE_URL,
    // The design's own width. The breakpoint assertions set their own viewport.
    viewport: { width: 1440, height: 900 },
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    actionTimeout: 15_000,
    navigationTimeout: 30_000,
  },

  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],

  webServer: [
    {
      command: "node e2e/support/backend.mjs",
      // permitAll in SecurityConfig and a 200 with no database work, so it says
      // "the application is up" without saying anything about authorization.
      url: "http://localhost:8080/v3/api-docs",
      reuseExistingServer: !process.env.CI,
      // Gradle compiles, Flyway migrates, and the descriptor registry validates
      // before the port opens. A minute is not enough on a cold daemon.
      timeout: 300_000,
      stdout: "pipe",
      stderr: "pipe",
    },
    {
      // The production build, not `next dev`. Dev compiles each route on first
      // request, which turns the first navigation of every spec into a timeout
      // risk, and a suite whose flakiness depends on which test ran first proves
      // nothing.
      command: "npm run e2e:web",
      url: BASE_URL,
      reuseExistingServer: !process.env.CI,
      timeout: 300_000,
      stdout: "pipe",
      stderr: "pipe",
    },
  ],
});
