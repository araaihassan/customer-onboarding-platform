import { expect, test } from "@playwright/test";
import {
  Api,
  apiContext,
  provisionTenant,
  readEmailToken,
  seedUser,
  signIn,
} from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Customer management, and the two halves of permission gating.
 *
 * `useHasPermission` hides a control the user cannot use. That is a courtesy, and
 * this file asserts the control it is a courtesy for: the button is absent AND the
 * endpoint refuses a direct call made from the signed-in page itself.
 */
let tenant: Tenant;
let customerId: string;
let contactEmail: string;
/** Holds customer.view and contact.view, but NOT customer.create. */
let viewerEmail: string;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);

  tenant = await provisionTenant(request, "cust");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  // The customer is seeded through the API because three tests need one to
  // exist and only the first of them is about creating it. The CONTACT is
  // deliberately NOT seeded: it used to be, and that is precisely why a green
  // 27-test suite never noticed that nothing in the interface could create one.
  // Spec §12's definition of done says contacts can be created and invited, so
  // the suite creates one the way a user does.
  const seeded = await admin.createCustomer("Contoso Logistics");
  customerId = seeded.id;
  contactEmail = `ops@${tenant.slug}.test`;

  viewerEmail = await seedUser(request, admin, tenant, "viewer", {
    "customer.view": "ALL",
    "contact.view": "ALL",
  });

  await request.dispose();
});

test("creates a customer and sees it in the list", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers`);

  await page.getByRole("button", { name: "New customer" }).click();

  const dialog = page.getByRole("dialog", { name: "New customer" });
  await dialog.getByLabel("Display name").fill("Fabrikam Freight");
  await dialog.getByLabel("Legal name").fill("Fabrikam Freight Ltd");
  await dialog.getByLabel("Industry").fill("Logistics");
  await dialog.getByLabel("Country").fill("GB");
  await dialog.getByRole("button", { name: "Create customer" }).click();

  // Straight to the record it just created — the form navigates on success.
  await expect(page.getByRole("heading", { level: 1, name: "Fabrikam Freight" })).toBeVisible();

  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("link", { name: "Fabrikam Freight" })).toBeVisible();
});

/**
 * Below 1024px the table stops being a table and becomes a two-line card list. A
 * table cannot be made to fit a phone by shrinking it.
 *
 * In Playwright rather than a unit test because jsdom does no layout: there, a
 * `lg:hidden` class is a string, and both views are "present" whatever the
 * viewport says.
 */
test("the customer table becomes a card list below 1024px", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.locator('[data-view="table"]')).toBeVisible();
  await expect(page.locator('[data-view="cards"]')).toBeHidden();
  // The same record is reachable either way, under the same accessible name.
  await expect(page.getByRole("link", { name: "Contoso Logistics" })).toBeVisible();

  await page.setViewportSize({ width: 1023, height: 900 });
  await expect(page.locator('[data-view="table"]')).toBeHidden();
  await expect(page.locator('[data-view="cards"]')).toBeVisible();
  await expect(page.getByRole("link", { name: "Contoso Logistics" })).toBeVisible();

  // And the page itself never scrolls sideways, which is the failure a table
  // squeezed below its breakpoint actually produces.
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
  );
  expect(overflow, "the page scrolls horizontally at 1023px").toBe(false);
});

/**
 * Both halves of spec §12's "customers and contacts can be created and invited",
 * in one test and through the interface end to end.
 *
 * Creation is here rather than seeded in `beforeAll` on purpose. When the contact
 * arrived through the API this file passed while the product had no way at all to
 * add one — the suite was asserting the invitation button on a record no user
 * could have produced. Seeding it back would reopen exactly that blind spot.
 */
test("adds a contact through the interface and sends it an invitation", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}`);

  await expect(page.getByRole("heading", { name: "Contacts" })).toBeVisible();
  await expect(page.getByText("No contacts yet")).toBeVisible();

  await page.getByRole("button", { name: "Add contact" }).click();

  const dialog = page.getByRole("dialog", { name: "Add contact" });
  await dialog.getByLabel("Full name").fill("Jordan Vale");
  await dialog.getByLabel("Email").fill(contactEmail);
  await dialog.getByLabel("Job title").fill("Head of Operations");
  await dialog.getByLabel("Primary contact").check();
  await dialog.getByRole("button", { name: "Create contact" }).click();

  // Success is visible: the dialog closes and the person is on the page.
  await expect(dialog).toBeHidden();
  const added = page.getByRole("listitem").filter({ hasText: "Jordan Vale" });
  await expect(added.getByText(contactEmail)).toBeVisible();
  // The flag survived the round trip, and it is marked with a word rather than
  // only a colour.
  await expect(added.getByText("Primary")).toBeVisible();

  await page.getByRole("button", { name: "Send invitation to Jordan Vale" }).click();

  // The button is spent and says so — an invitation already on its way is not
  // something to offer again on the same screen. Scoped to the row, because the
  // list also carries a visually-hidden live region saying the same words, and
  // that is the other half of the assertion.
  const row = page.getByRole("listitem").filter({ hasText: "Jordan Vale" });
  await expect(row.getByText("Invitation sent")).toBeVisible();
  await expect(row.getByRole("button", { name: "Send invitation to Jordan Vale" })).toHaveCount(0);
  // Announced, not only shown.
  await expect(page.getByRole("status")).toHaveText("Invitation sent");

  // And it really went out. The button turning to "Invitation sent" is a claim
  // the client makes about itself; a token in the log is the invitation existing.
  const token = await readEmailToken(contactEmail, "Activate your portal account");
  expect(token).toMatch(/^[A-Za-z0-9_-]{20,}$/);
});

/**
 * Both halves, in one test on purpose. Splitting them invites someone to delete
 * the second one as "already covered by the button assertion", which is precisely
 * the confusion the pair exists to prevent.
 */
test("a user without customer.create cannot create one, by button or by endpoint", async ({
  page,
}) => {
  await signIn(page, tenant.slug, viewerEmail);
  await page.goto(`/t/${tenant.slug}/customers`);

  // The screen works — this user can read customers.
  await expect(page.getByRole("heading", { level: 1, name: "Customers" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Contoso Logistics" })).toBeVisible();

  // Half one: the affordance is absent.
  await expect(page.getByRole("button", { name: "New customer" })).toHaveCount(0);

  // Half two: the endpoint refuses anyway, called from inside the signed-in page
  // with that user's own session. The access token lives in a module closure the
  // page cannot reach, so this trades the HttpOnly refresh cookie for a fresh one
  // exactly as any attacker with the console open would.
  const status = await page.evaluate(async (slug) => {
    const refreshed = await fetch(`/api/t/${slug}/auth/refresh`, {
      method: "POST",
      credentials: "include",
    });
    const { accessToken } = (await refreshed.json()) as { accessToken: string };

    const created = await fetch(`/api/t/${slug}/customers`, {
      method: "POST",
      credentials: "include",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify({ displayName: "Smuggled", legalName: "Smuggled Ltd" }),
    });
    return created.status;
  }, tenant.slug);

  // 403, not 404: customer.create is ALL-only and has no record to be out of
  // scope of, so this is a refused permission rather than a hidden record.
  expect(status, "the endpoint accepted a create from a user without customer.create").toBe(403);

  // And nothing was written. The heading first, deliberately: a `toHaveCount(0)`
  // on its own also holds when the page failed to render or bounced to login,
  // which would let this assertion pass for a reason that has nothing to do with
  // the record being absent.
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("heading", { level: 1, name: "Customers" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Contoso Logistics" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Smuggled" })).toHaveCount(0);
});
