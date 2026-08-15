import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * The accessibility baseline, held by something.
 *
 * The design's accessibility work — review findings 1 through 14, the focus ring
 * the prototype never had, the switch roles, the colour-plus-word rule — is worth
 * having only if a test fails when it erodes. This is that test.
 *
 * **Both themes.** The dark theme has never been reviewed at screen level, and the
 * shipped LIGHT tokens are measured by nothing at all: `contrast.py`'s light table
 * deliberately preserves the prototype's pre-fix values as review evidence, so a
 * run of this suite is the first real measurement the light theme has had.
 *
 * No rule is disabled anywhere in this file. If axe reports something, it is a
 * finding — including the two painted pairs the token audit does not cover
 * (`status-blocked-fg` as error text, `graphic-muted` as the empty-state icon).
 */
let tenant: Tenant;
let customerId: string;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "a11y");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const customer = await admin.createCustomer("Tailspin Toys");
  customerId = customer.id;
  await admin.createContact(customerId, "Robin Ash", `robin@${tenant.slug}.test`);
  await request.dispose();
});

const THEMES = ["light", "dark"] as const;

/**
 * Every authenticated screen the sweeps below walk, with the <h1> each one puts
 * in the shell header via `useSetPageHeader`.
 *
 * The heading is the precondition, not decoration. Waiting on the rail would only
 * prove the layout rendered — it lives above the router outlet, so it survives a
 * page throwing into Next's error boundary, and a sweep that found no unlabelled
 * controls on a screen that never rendered would be a green test for a blank
 * page.
 */
const SCREENS: { path: string; heading: string }[] = [
  { path: "dashboard", heading: "Dashboard" },
  { path: "customers", heading: "Customers" },
  { path: "admin/roles", heading: "Roles" },
  { path: "admin/users", heading: "Users" },
  { path: "admin/org", heading: "Organisation" },
];

/**
 * next-themes writes `data-theme` on <html>, not a class, and the dark tokens are
 * keyed on the attribute. Setting localStorage before the page loads is what makes
 * the first paint the theme under test — flipping it afterwards would leave axe
 * measuring whatever the transition had reached.
 */
async function useTheme(page: Page, theme: (typeof THEMES)[number]) {
  await page.addInitScript((value) => {
    window.localStorage.setItem("theme", value);
  }, theme);
}

async function assertTheme(page: Page, theme: (typeof THEMES)[number]) {
  await expect(page.locator("html")).toHaveAttribute("data-theme", theme);
}

/**
 * axe's full default rule set — no `withTags` filter, no `disableRules`, no
 * excluded selectors. A rule turned off to get green is the one change that turns
 * this file into decoration.
 *
 * The gate is serious and critical, which is the spec's own bar (§12 item 7). At
 * the time of writing all three screens report **zero violations at every
 * impact** in both themes, so anything that appears below the gate is new too,
 * and worth looking at rather than raising the bar to hide.
 */
async function scan(page: Page) {
  const results = await new AxeBuilder({ page }).analyze();
  return results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
}

function describeViolations(violations: Awaited<ReturnType<typeof scan>>): string {
  return violations
    .map((v) => `${v.id} (${v.impact}): ${v.help}\n  ${v.nodes.map((n) => n.target.join(" ")).join("\n  ")}`)
    .join("\n");
}

for (const theme of THEMES) {
  test.describe(`${theme} theme`, () => {
    test("login has no serious or critical violations", async ({ page }) => {
      await useTheme(page, theme);
      await page.goto(`/t/${tenant.slug}/login`);
      await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
      await assertTheme(page, theme);

      const violations = await scan(page);
      expect(describeViolations(violations)).toBe("");
    });

    test("the customer list has no serious or critical violations", async ({ page }) => {
      await useTheme(page, theme);
      await signIn(page, tenant.slug, tenant.adminEmail);
      await page.goto(`/t/${tenant.slug}/customers`);
      await expect(page.getByRole("link", { name: "Tailspin Toys" })).toBeVisible();
      await assertTheme(page, theme);

      const violations = await scan(page);
      expect(describeViolations(violations)).toBe("");
    });

    test("the role editor has no serious or critical violations", async ({ page }) => {
      await useTheme(page, theme);
      await signIn(page, tenant.slug, tenant.adminEmail);
      await page.goto(`/t/${tenant.slug}/admin/roles`);
      // The editor, not merely the page: the catalog and the inspector are what
      // this screen is, and they arrive after two requests.
      await expect(page.getByRole("heading", { name: "Permissions" })).toBeVisible();
      await expect(page.getByRole("switch").first()).toBeVisible();
      await assertTheme(page, theme);

      const violations = await scan(page);
      expect(describeViolations(violations)).toBe("");
    });
  });
}

/**
 * Review finding 2: the prototype had no focus indicator anywhere, a straight
 * WCAG 2.4.7 failure, and it is the single easiest regression to reintroduce —
 * one `outline: none` on one component restores it.
 *
 * Measured, not asserted from the stylesheet: a rule that exists but is
 * overridden by an inline style would pass a CSS assertion and fail a user.
 */
test("every tab stop on the customer list shows a visible focus indicator", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("link", { name: "Tailspin Toys" })).toBeVisible();

  const unmarked: string[] = [];

  for (let i = 0; i < 30; i += 1) {
    await page.keyboard.press("Tab");

    const stop = await page.evaluate(() => {
      const element = document.activeElement;
      if (!element || element === document.body) return null;

      const style = window.getComputedStyle(element);
      const outlineWidth = Number.parseFloat(style.outlineWidth) || 0;
      const visibleOutline =
        outlineWidth > 0 && style.outlineStyle !== "none" && style.outlineColor !== "transparent";

      return {
        // Enough to name the offender in a failure message.
        description: `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ""}` +
          `[${(element.getAttribute("aria-label") ?? element.textContent ?? "").trim().slice(0, 40)}]`,
        // Either a real outline or a ring drawn with box-shadow counts; both are
        // visible, and the design uses the first.
        focusVisible: visibleOutline || style.boxShadow !== "none",
      };
    });

    if (!stop) break;
    if (!stop.focusVisible) unmarked.push(stop.description);
  }

  expect(unmarked, "tab stops with no visible focus indicator").toEqual([]);
});

/**
 * A conditional sweep, not an existence assertion.
 *
 * The customer screens carry no progress bar — `CustomerView` has no progress
 * field, and the design's Customers screen lists cases, which arrive in
 * sub-project 2. So this asserts the invariant rather than the presence: wherever
 * a progress bar appears, its `aria-valuenow` must equal the percentage a sighted
 * reader sees. In the prototype those are separate DOM text and can disagree.
 */
test("any progress bar exposes an aria-valuenow matching its visible percentage", async ({
  page,
}) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  for (const { path, heading } of [
    ...SCREENS,
    { path: `customers/${customerId}`, heading: "Tailspin Toys" },
  ]) {
    await page.goto(`/t/${tenant.slug}/${path}`);
    await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();

    const mismatches = await page.evaluate(() =>
      Array.from(document.querySelectorAll('[role="progressbar"]'))
        .map((bar) => {
          const now = bar.getAttribute("aria-valuenow");
          const shown = (bar.textContent ?? "").match(/(\d+(?:\.\d+)?)\s*%/)?.[1];
          if (shown === undefined) return null;
          return now === shown ? null : `aria-valuenow=${now} but shows ${shown}%`;
        })
        .filter((entry): entry is string => entry !== null),
    );

    expect(mismatches, `progress bars on /${path}`).toEqual([]);
  }
});

/**
 * An icon-only control with no accessible name is unusable to anyone not looking
 * at it, and it is the failure mode a design system full of 13px icons invites.
 * Swept across every authenticated screen rather than asserted per component,
 * because the one that goes wrong is always the one nobody wrote a test for.
 */
test("no interactive element is an unlabelled icon-only control", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  for (const { path, heading } of [
    ...SCREENS,
    { path: `customers/${customerId}`, heading: "Tailspin Toys" },
  ]) {
    await page.goto(`/t/${tenant.slug}/${path}`);
    await expect(page.getByRole("heading", { level: 1, name: heading })).toBeVisible();

    const unnamed = await page.evaluate(() =>
      Array.from(
        document.querySelectorAll<HTMLElement>(
          'button, a[href], [role="switch"], [role="button"], input:not([type="hidden"]), select',
        ),
      )
        .filter((element) => {
          const ariaLabel = element.getAttribute("aria-label") ?? "";
          const labelledBy = (element.getAttribute("aria-labelledby") ?? "")
            .split(/\s+/)
            .filter(Boolean)
            .map((id) => document.getElementById(id)?.textContent ?? "")
            .join(" ");
          const title = element.getAttribute("title") ?? "";
          const text = element.textContent ?? "";
          // A form control's <label for> is an accessible name too.
          const wrappedLabel = element.id
            ? (document.querySelector(`label[for="${CSS.escape(element.id)}"]`)?.textContent ?? "")
            : "";

          return ![ariaLabel, labelledBy, title, text, wrappedLabel].some(
            (candidate) => candidate.trim().length > 0,
          );
        })
        .map(
          (element) =>
            `${element.tagName.toLowerCase()}${element.className ? `.${String(element.className).split(" ")[0]}` : ""}`,
        ),
    );

    expect(unnamed, `unlabelled controls on /${path}`).toEqual([]);
  }
});
