import { expect, test } from "@playwright/test";
import type { APIRequestContext } from "@playwright/test";
import { PASSWORD, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Signing in, staying signed in, and signing out.
 *
 * One tenant for the file: provisioning is the expensive part, and none of these
 * tests mutates anything another one reads.
 */
let tenant: Tenant;

test.beforeAll(async ({ playwright }) => {
  const request: APIRequestContext = await playwright.request.newContext({
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
  });
  tenant = await provisionTenant(request, "auth");
  await request.dispose();
});

test("signs in with valid credentials and reaches the dashboard", async ({ page }) => {
  await page.goto(`/t/${tenant.slug}/login`);
  await page.getByLabel("Email").fill(tenant.adminEmail);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/dashboard$`));
  // The shell, not merely the URL: the header's <h1> is set by the page, so it
  // only appears once the authenticated screen has actually rendered.
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toBeVisible();
});

test("invalid credentials show the error and stay on the login page", async ({ page }) => {
  await page.goto(`/t/${tenant.slug}/login`);
  await page.getByLabel("Email").fill(tenant.adminEmail);
  await page.getByLabel("Password").fill("not-the-right-password");
  await page.getByRole("button", { name: "Sign in" }).click();

  // Scoped to the form: Next renders a permanently-present, empty
  // `role="alert"` route announcer on every page, so an unscoped alert lookup
  // matches two elements and fails strict mode.
  await expect(page.locator("form").getByRole("alert")).toHaveText(
    "Invalid email or password",
  );
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));
  // Still signed out, not merely still on the page.
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toHaveCount(0);
});

/**
 * The flow most likely to regress. The access token lives only in a module-scoped
 * variable and does not survive a reload; the HttpOnly refresh cookie does, and
 * AuthProvider trades it for a new token on mount. If silent refresh breaks, this
 * is the test that notices.
 */
test("reloading while signed in keeps the session", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  await page.reload();

  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/dashboard$`));
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();

  // A hard navigation to another authenticated screen, which is a second, harsher
  // version of the same proof: nothing about the session is held in this document.
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("heading", { level: 1, name: "Customers" })).toBeVisible();
});

test("signing out cannot be undone with the back button", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  // A pushed entry, so there is somewhere to go back TO. Login and the guard's
  // redirect both use router.replace, so a sign-in on its own leaves history with
  // a single application entry and `goBack` lands on about:blank — which would
  // pass this test while proving nothing.
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("heading", { level: 1, name: "Customers" })).toBeVisible();

  await page.getByRole("button", { name: /Account menu for/ }).click();
  await page.getByRole("button", { name: "Sign out" }).click();

  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));

  await page.goBack();

  // The authenticated view must not come back. Whether the browser serves the
  // dashboard URL from history or not, the guard re-runs and the refresh cookie
  // is gone server-side, so what renders is the login page.
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
});
