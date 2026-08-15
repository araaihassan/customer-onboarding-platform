import { expect, test } from "@playwright/test";
import type { APIRequestContext } from "@playwright/test";
import { Api, PASSWORD, provisionTenant, readEmailToken } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * A customer contact is invited, activates, and signs in as a PORTAL user.
 *
 * The token is read from the backend's log, which is the only place it exists: the
 * invitation endpoint answers 204 with no body precisely so a caller never holds a
 * credential for someone else's account, and `LoggingEmailSender` — dev and test
 * profiles only — is what makes it recoverable at all.
 *
 * Two contacts, one per test. Sharing one would make the second test depend on the
 * first having already consumed the token, and a suite whose result depends on
 * execution order tells you nothing when it goes red.
 */
let tenant: Tenant;
const contacts: Record<"first" | "second", string> = { first: "", second: "" };

test.beforeAll(async ({ playwright }) => {
  const request: APIRequestContext = await playwright.request.newContext({
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
  });

  tenant = await provisionTenant(request, "activate");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const { id: customerId } = await admin.createCustomer("Northwind Foods");

  for (const key of ["first", "second"] as const) {
    const email = `${key}-contact@${tenant.slug}.test`;
    const { id: contactId } = await admin.createContact(customerId, `Dana ${key}`, email);
    await admin.sendInvitation(customerId, contactId);
    contacts[key] = email;
  }

  await request.dispose();
});

test("a contact activates their invitation and signs in as a portal user", async ({ page }) => {
  const email = contacts.first;
  const token = await readEmailToken(email, "Activate your portal account");

  await page.goto(`/t/${tenant.slug}/activate?token=${encodeURIComponent(token)}`);
  await page.getByLabel("Choose a password").fill(PASSWORD);
  await page.getByLabel("Confirm password").fill(PASSWORD);
  await page.getByRole("button", { name: "Activate" }).click();

  // Activation issues no session — it answers 204 — so the only correct
  // destination is login.
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));

  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();

  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/dashboard$`));
  await expect(page.getByRole("heading", { level: 1, name: "Dashboard" })).toBeVisible();

  // A PORTAL user holds no internal role — RoleService refuses to assign one — so
  // the rail carries Dashboard and nothing else. Hiding those entries is a
  // courtesy; the endpoints refuse independently.
  const nav = page.getByRole("navigation", { name: "Main navigation" });
  await expect(nav.getByRole("link")).toHaveText(["Dashboard"]);
});

test("the same activation link fails on a second attempt", async ({ page }) => {
  const email = contacts.second;
  const token = await readEmailToken(email, "Activate your portal account");
  const url = `/t/${tenant.slug}/activate?token=${encodeURIComponent(token)}`;

  await page.goto(url);
  await page.getByLabel("Choose a password").fill(PASSWORD);
  await page.getByLabel("Confirm password").fill(PASSWORD);
  await page.getByRole("button", { name: "Activate" }).click();
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/login$`));

  // The identical link, a second time.
  await page.goto(url);
  await page.getByLabel("Choose a password").fill("another-password-entirely");
  await page.getByLabel("Confirm password").fill("another-password-entirely");
  await page.getByRole("button", { name: "Activate" }).click();

  await expect(page.locator("form").getByRole("alert")).toHaveText(
    "That link is invalid or has expired. Ask for a new one.",
  );
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/activate`));

  // And the second attempt set nothing: the password from the first still works.
  // A single-use token that silently reset the password would pass an assertion
  // about the error message alone.
  await page.goto(`/t/${tenant.slug}/login`);
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/dashboard$`));
});
