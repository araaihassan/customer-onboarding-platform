import { expect, test } from "@playwright/test";
import { apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * The administration screens, driven as an administrator would.
 *
 * Not in Task 28's file list, and kept anyway: spec §12's Definition of Done item
 * 2 is "a tenant administrator logs in, creates departments, teams, and users,
 * assigns roles, and edits role grants — with invalid permission/scope
 * combinations rejected", and nothing else in the suite asserts any of it. The
 * brief's four named specs cover the auth flows; these three cover the screens
 * this task built, which would otherwise ship with their rendering verified and
 * their behaviour not.
 *
 * The invalid-combination half of item 2 is enforced in the editor rather than
 * asserted here: the scope control only ever offers what the catalog allows, and
 * `RoleEditor.test.tsx` proves that against a hardcoded-list implementation.
 */
let tenant: Tenant;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "smoke");
  await request.dispose();
});

test("users screen: invite, assign a role, deactivate", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/users`);

  await expect(page.getByRole("heading", { level: 1, name: "Users" })).toBeVisible();
  await expect(page.getByText(tenant.adminEmail)).toBeVisible();
  await expect(page.getByText("Administrator")).toBeVisible();

  await page.getByRole("button", { name: "Invite user" }).click();
  const dialog = page.getByRole("dialog", { name: "Invite user" });
  await dialog.getByLabel("Email").fill(`colleague@${tenant.slug}.test`);
  await dialog.getByLabel("Full name").fill("Sam Colleague");
  await dialog.getByRole("button", { name: "Send invitation" }).click();

  await expect(page.getByText("Sam Colleague")).toBeVisible();
  const row = page.getByRole("listitem").filter({ hasText: "Sam Colleague" });
  await expect(row.getByText("Invited")).toBeVisible();
  await expect(row.getByText("No roles")).toBeVisible();

  await row.getByRole("button", { name: "Manage roles for Sam Colleague" }).click();
  const roles = page.getByRole("dialog", { name: "Manage roles for Sam Colleague" });
  await roles.getByRole("button", { name: "Assign Support" }).click();
  await expect(roles.getByRole("button", { name: "Remove Support" })).toBeVisible();
  await roles.getByRole("button", { name: "Close" }).click();
  await expect(row.getByText("Support")).toBeVisible();

  // Deactivation, never deletion — there is no delete on a user, and no control
  // for one anywhere on this screen.
  await expect(row.getByRole("button", { name: /delete/i })).toHaveCount(0);
  await row.getByRole("button", { name: "Deactivate Sam Colleague" }).click();
  await page
    .getByRole("dialog", { name: "Deactivate user" })
    .getByRole("button", { name: "Confirm" })
    .click();
  await expect(row.getByText("Deactivated")).toBeVisible();
});

test("org screen: create a department and a team in it", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/org`);

  await expect(page.getByRole("heading", { level: 1, name: "Organisation" })).toBeVisible();

  await page.getByRole("button", { name: "New department" }).click();
  const dept = page.getByRole("dialog", { name: "New department" });
  await dept.getByLabel("Name").fill("Onboarding");
  await dept.getByLabel("Description").fill("Runs activations");
  await dept.getByRole("button", { name: "Create department" }).click();
  await expect(page.getByText("Onboarding")).toBeVisible();

  await page.getByRole("button", { name: "New team" }).click();
  const team = page.getByRole("dialog", { name: "New team" });
  await team.getByLabel("Name").fill("EMEA Pod");
  await team.getByLabel("Description").fill("Europe");
  await team.getByLabel("Department").selectOption({ label: "Onboarding" });
  await team.getByRole("button", { name: "Create team" }).click();
  await expect(page.getByText("EMEA Pod")).toBeVisible();
  await expect(page.getByText("Europe · Onboarding")).toBeVisible();
});

test("role editor: rescope a grant and save it", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/roles`);

  await page.getByRole("button", { name: /^Support/ }).click();
  const row = page.getByRole("listitem").filter({ hasText: "View customers" });
  await expect(row.getByRole("switch")).toHaveAttribute("aria-checked", "true");
  await row.getByRole("combobox").selectOption("DEPARTMENT");

  await page.getByRole("button", { name: "Save grants" }).click();
  await expect(page.getByRole("status")).toHaveText("Grants saved");

  await page.reload();
  await page.getByRole("button", { name: /^Support/ }).click();
  await expect(
    page.getByRole("listitem").filter({ hasText: "View customers" }).getByRole("combobox"),
  ).toHaveValue("DEPARTMENT");
});
