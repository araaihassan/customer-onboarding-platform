import { expect, test } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
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
  // A MULTI-WORD seeded role, deliberately. The first version of this test
  // assigned "Support", and a single word survives StatusPill's humanise()
  // unchanged — which is exactly why the role chip rendering "Sales
  // representative" for "Sales Representative" went unnoticed.
  await roles.getByRole("button", { name: "Assign Account Manager" }).click();
  await expect(roles.getByRole("button", { name: "Remove Account Manager" })).toBeVisible();
  await roles.getByRole("button", { name: "Close" }).click();

  // Exact text: the name is tenant-authored human text and must reach the
  // accessibility tree as its author wrote it, not lower-cased or uppercased.
  await expect(row.getByText("Account Manager", { exact: true })).toBeVisible();

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

/**
 * A failed catalog fetch must not be rendered as "this user holds no roles".
 *
 * Route interception rather than a contrived tenant: the failure under test is a
 * 500 from `/admin/roles`, and there is no way to provoke one from real data.
 * Everything else on the screen is genuine.
 */
test("a failed roles fetch is reported, never rendered as 'No roles'", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  await page.route("**/api/t/*/admin/roles", (route) =>
    route.fulfill({ status: 500, contentType: "application/json", body: "{}" }),
  );

  await page.goto(`/t/${tenant.slug}/admin/users`);
  await expect(page.getByRole("heading", { level: 1, name: "Users" })).toBeVisible();

  const row = page.getByRole("listitem").filter({ hasText: tenant.adminEmail });

  // The screen says what went wrong and offers a way out...
  await expect(
    page.getByRole("alert").filter({ hasText: "Roles could not be loaded" }),
  ).toBeVisible();
  // ...the row admits it does not know...
  await expect(row.getByText("Roles unknown")).toBeVisible();
  // ...and never claims the opposite. The administrator demonstrably holds a
  // role, so "No roles" here would be a false statement about authorization.
  await expect(row.getByText("No roles")).toHaveCount(0);
  // The trigger is gone too: the dialog it opens lists roles, and would open
  // onto an empty list reading as "this tenant has none".
  await expect(row.getByRole("button", { name: /^Manage roles for/ })).toHaveCount(0);
});

/**
 * The list requests 25 and reports the total. Without a pager, a tenant with more
 * than 25 users shows a count it cannot reach — search being the only route to
 * anybody past the first page.
 */
test("the user list pages through more users than fit on one page", async ({
  page,
  playwright,
}) => {
  const request = await apiContext(playwright);
  const paged = await provisionTenant(request, "paged");
  const admin = await Api.as(request, paged.slug, paged.adminEmail);

  // 25 is the page size, so 26 plus the administrator is two pages with a
  // deliberately short second one.
  for (let i = 0; i < 26; i += 1) {
    const padded = String(i).padStart(2, "0");
    await admin.createUser(`member${padded}@${paged.slug}.test`, `Member ${padded}`);
  }
  await request.dispose();

  await signIn(page, paged.slug, paged.adminEmail);
  await page.goto(`/t/${paged.slug}/admin/users`);
  await expect(page.getByRole("heading", { level: 1, name: "Users" })).toBeVisible();

  await expect(page.getByText("27 shown")).toBeVisible();
  // Scoped to <main>: the rail's nav links are list items too, and an unscoped
  // count silently includes them.
  const rows = page.locator("main").getByRole("listitem");
  await expect(rows).toHaveCount(25);

  const pager = page.getByRole("navigation", { name: "User list pages" });
  await expect(pager.getByText("Page 1 of 2")).toBeVisible();
  await expect(pager.getByRole("button", { name: "Previous page" })).toBeDisabled();

  await pager.getByRole("button", { name: "Next page" }).click();
  await expect(pager.getByText("Page 2 of 2")).toBeVisible();
  await expect(rows).toHaveCount(2);
  await expect(pager.getByRole("button", { name: "Next page" })).toBeDisabled();

  await pager.getByRole("button", { name: "Previous page" }).click();
  await expect(pager.getByText("Page 1 of 2")).toBeVisible();
  await expect(rows).toHaveCount(25);
});
