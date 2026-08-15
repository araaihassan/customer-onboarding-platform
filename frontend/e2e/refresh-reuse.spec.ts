import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Refresh-token rotation, and what happens when a retired token comes back.
 *
 * Rejecting the replayed token is the easy half, and on its own it proves almost
 * nothing — a backend that simply deleted each token on use would pass it. What
 * makes reuse detection meaningful is that the LEGITIMATE session dies too: the
 * whole family is revoked, so the victim is signed out and notices. That is the
 * assertion this file exists for, and it is asserted three ways.
 */
let tenant: Tenant;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "reuse");
  await request.dispose();
});

/** The HttpOnly refresh cookie, read through the browser context rather than JS. */
async function refreshCookie(page: Page): Promise<string> {
  const cookies = await page.context().cookies();
  const cookie = cookies.find((c) => c.name === "refresh_token");
  expect(cookie, "no refresh_token cookie was set").toBeTruthy();
  return cookie?.value ?? "";
}

test("rotation replaces the refresh token on every use", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  const first = await refreshCookie(page);

  // A reload is a real refresh: the access token lives only in a module-scoped
  // variable and does not survive one, so AuthProvider trades the cookie for a
  // new token on mount — and the backend rotates the cookie while doing it.
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();

  expect(await refreshCookie(page), "the cookie was not rotated").not.toBe(first);
});

test("replaying a retired refresh token kills the legitimate session too", async ({
  page,
  playwright,
}) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  // The token about to be retired — what a thief captures.
  const stolen = await refreshCookie(page);

  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();

  const live = await refreshCookie(page);
  expect(live, "the reload did not rotate the token").not.toBe(stolen);

  // A context with its own empty cookie jar, so exactly one refresh_token reaches
  // the backend. Sharing the page's jar would send `live` alongside `stolen` and
  // the backend would read whichever came first.
  const thief = await apiContext(playwright);
  const replayed = await thief.post(`/api/t/${tenant.slug}/auth/refresh`, {
    headers: { Cookie: `refresh_token=${stolen}` },
  });

  // Half one: the stolen token is refused.
  expect(replayed.status(), "a retired refresh token must be rejected").toBe(401);

  // Half two, and the one that matters. The victim's next navigation is a full
  // page load, so their in-memory access token is gone and AuthProvider must trade
  // the cookie it still holds for a new one. If only the replayed token had been
  // revoked, `live` would still work and this would land on the customer list.
  //
  // This is asserted BEFORE probing `live` directly, and the order is
  // load-bearing: probing first would consume and rotate `live` if the family had
  // NOT been revoked, and the browser would then be bounced for the wrong reason —
  // a false green on exactly the assertion this file exists for.
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));
  await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Main navigation" })).toHaveCount(0);

  // Half three: the same claim without the frontend in the way. The legitimate,
  // never-replayed token is itself dead — the family was revoked, not just the
  // one token that was reused. The browser's failed attempt above did not rotate
  // it, because a rejected refresh issues nothing.
  const victim = await apiContext(playwright);
  const legitimate = await victim.post(`/api/t/${tenant.slug}/auth/refresh`, {
    headers: { Cookie: `refresh_token=${live}` },
  });
  expect(
    legitimate.status(),
    "the legitimate token survived a reuse — the family was not revoked",
  ).toBe(401);

  await thief.dispose();
  await victim.dispose();

  // And signing in again works: reuse detection revokes a family, it does not
  // lock the account.
  await signIn(page, tenant.slug, tenant.adminEmail);
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();
});
