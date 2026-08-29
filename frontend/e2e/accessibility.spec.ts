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
 * **Light only.** The design system this suite measures against ships no dark
 * theme (`docs/uispecs_latest/.../README.md`'s non-negotiables) and the app no
 * longer writes a `data-theme` attribute at all -- `next-themes` was removed
 * along with the dark palette. Light is the only mode that exists, so it is
 * the only one swept here.
 *
 * No rule is disabled anywhere in this file. If axe reports something, it is a
 * finding — including the two painted pairs the token audit does not cover
 * (`status-blocked-fg` as error text, `graphic-muted` as the empty-state icon).
 */
let tenant: Tenant;
let customerId: string;
let caseId: string;
let builderTemplateId: string;
let builderVersionId: string;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "a11y");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const customer = await admin.createCustomer("Tailspin Toys");
  customerId = customer.id;
  await admin.createContact(customerId, "Robin Ash", `robin@${tenant.slug}.test`);

  // A published workflow with a real case against it, so the journey
  // workspace sweep hits the roadmap's milestone rows rather than the "no
  // cases" empty state.
  const { templateId } = await admin.publishMinimalWorkflow("Onboarding");
  const openCase = await admin.createCase(customerId, templateId);
  caseId = openCase.id;

  // A second template, left as a draft, so the builder sweep hits the real
  // editor -- inspector, switches, branch-rule affordance -- rather than a
  // frozen, read-only version.
  builderTemplateId = (await admin.createWorkflowTemplate("Onboarding Draft")).id;
  const draft = await admin.createDraftVersion(builderTemplateId);
  builderVersionId = draft.versionId;
  // estimatedDurationDays is the one MilestoneRequest field the API actually
  // validates (@Positive int) -- omitting it, as CLAUDE.md's own Playwright
  // notes warn, 400s rather than defaulting. dependsOnMilestoneKeys and
  // branchRules NPE if left out entirely (no null guard downstream), so both
  // are explicit empty arrays rather than omitted.
  await admin.saveDraft(builderTemplateId, builderVersionId, {
    stages: [{
      key: "stage-1",
      name: "Registration",
      milestones: [{
        key: "milestone-1",
        name: "Registration",
        estimatedDurationDays: 1,
        dependsOnMilestoneKeys: [],
        requirements: [],
      }],
      branchRules: [],
    }],
    attributes: [],
    lockVersion: draft.lockVersion,
  });

  await request.dispose();
});

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
 * axe's full default rule set — no `withTags` filter, no `disableRules`, no
 * excluded selectors. A rule turned off to get green is the one change that turns
 * this file into decoration.
 *
 * The gate is serious and critical, which is the spec's own bar (§12 item 7). At
 * the time of writing all three screens report **zero violations at every
 * impact**, so anything that appears below the gate is new too, and worth
 * looking at rather than raising the bar to hide.
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

test.describe("light theme", () => {
  test("login has no serious or critical violations", async ({ page }) => {
    await page.goto(`/t/${tenant.slug}/login`);
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

    const violations = await scan(page);
    expect(describeViolations(violations)).toBe("");
  });

  test("the customer list has no serious or critical violations", async ({ page }) => {
    await signIn(page, tenant.slug, tenant.adminEmail);
    await page.goto(`/t/${tenant.slug}/customers`);
    await expect(page.getByRole("link", { name: "Tailspin Toys" })).toBeVisible();

    const violations = await scan(page);
    expect(describeViolations(violations)).toBe("");
  });

  test("the role editor has no serious or critical violations", async ({ page }) => {
    await signIn(page, tenant.slug, tenant.adminEmail);
    await page.goto(`/t/${tenant.slug}/admin/roles`);
    // The editor, not merely the page: the catalog and the inspector are what
    // this screen is, and they arrive after two requests.
    await expect(page.getByRole("heading", { name: "Permissions" })).toBeVisible();
    await expect(page.getByRole("switch").first()).toBeVisible();

    const violations = await scan(page);
    expect(describeViolations(violations)).toBe("");
  });
});

/**
 * Task 28's own widths: 1440 (design canon), 1280 and 1024 (the two
 * breakpoints this task adds), 768 (below both, to prove nothing collapses
 * further down). Axe's default rule set evaluates `color-contrast` for TEXT
 * and has no non-text rule -- so a clean run here says nothing about a
 * border or a status circle's fill; that is `contrast.py`'s
 * `report_shipped` job, not this one's, and the two are not substitutes.
 */
const RESPONSIVE_WIDTHS = [1440, 1280, 1024, 768] as const;

for (const width of RESPONSIVE_WIDTHS) {
  test.describe(`light theme at ${width}px`, () => {
    test("journey workspace has no axe violations", async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await signIn(page, tenant.slug, tenant.adminEmail);
      await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`);
      // level: 1, as every other precondition heading check in this file does
      // (lines 306/378) -- the case workspace deliberately has a SECOND
      // heading with the same text at level 2 (CaseHeader's own card title,
      // SCREENS.md §3's "h1 27px = customer name"; TopBar's shared, page-wide
      // <h1> mechanism is what actually holds the h1 tag here, a prior fix
      // for a genuine duplicate-<h1> regression). An unscoped name-only
      // locator matches both and fails Playwright's strict mode -- a test
      // specificity gap, not a product defect.
      await expect(page.getByRole("heading", { level: 1, name: "Tailspin Toys" })).toBeVisible();
      await expect(page.getByTestId("milestone-row").first()).toBeVisible();

      const violations = await scan(page);
      expect(describeViolations(violations)).toBe("");
    });

    test("workflow builder has no axe violations", async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      await signIn(page, tenant.slug, tenant.adminEmail);
      await page.goto(`/t/${tenant.slug}/admin/workflows/${builderTemplateId}/versions/${builderVersionId}`);
      await expect(page.getByText("Registration").first()).toBeVisible();
      // The editor, not merely the list of stages: opens the inspector so
      // its fields and switches are part of what gets swept.
      await page.getByText("Registration").first().click();

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
 *
 * **The indicator must APPEAR on focus, not merely be present while focused.**
 * An earlier version of this test accepted any `box-shadow` other than `none` as
 * proof, which several components paint unconditionally — `TopBar`, `Dialog`, and
 * the role list's permanent selection ring. On any of those, an `outline: none`
 * regression would have passed. So each stop's computed style is captured while
 * focused, focus is then dropped, and the two are compared: a stop counts as
 * marked only if focusing it CHANGED the outline into something visible, or
 * changed the box-shadow.
 */
test("every tab stop on the customer list shows a visible focus indicator", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers`);
  await expect(page.getByRole("link", { name: "Tailspin Toys" })).toBeVisible();

  await page.evaluate(() => {
    (window as unknown as { __stops: unknown[] }).__stops = [];
  });

  let stops = 0;
  for (let i = 0; i < 40; i += 1) {
    await page.keyboard.press("Tab");

    const reached = await page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null;
      if (!element || element === document.body) return false;

      const style = window.getComputedStyle(element);
      (window as unknown as { __stops: unknown[] }).__stops.push({
        element,
        // Enough to name the offender in a failure message.
        description:
          `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ""}` +
          `[${(element.getAttribute("aria-label") ?? element.textContent ?? "").trim().slice(0, 40)}]`,
        focused: {
          outlineWidth: style.outlineWidth,
          outlineStyle: style.outlineStyle,
          outlineColor: style.outlineColor,
          boxShadow: style.boxShadow,
        },
      });
      return true;
    });

    if (!reached) break;
    stops += 1;
  }

  // Without this the loop could exit on the first Tab and `unmarked` would be
  // empty — a green test that checked nothing. The customer list carries the
  // rail's links, the two header controls, the search box, six filter chips, the
  // create button and the record links; ten is a floor well under that.
  expect(stops, "tab stops reached on the customer list").toBeGreaterThanOrEqual(10);

  const unmarked = await page.evaluate(() => {
    type Ring = {
      outlineWidth: string;
      outlineStyle: string;
      outlineColor: string;
      boxShadow: string;
    };
    type Stop = { element: HTMLElement; description: string; focused: Ring };

    // Drop focus so the comparison is against the resting state. :focus-visible
    // only applies while the element is focused from the keyboard, which is
    // exactly the difference being measured.
    (document.activeElement as HTMLElement | null)?.blur();

    return (window as unknown as { __stops: Stop[] }).__stops
      .filter((stop) => {
        const resting = window.getComputedStyle(stop.element);
        const { focused } = stop;

        const paintsOutline =
          (Number.parseFloat(focused.outlineWidth) || 0) > 0 &&
          focused.outlineStyle !== "none" &&
          focused.outlineColor !== "transparent";
        const outlineAppeared =
          paintsOutline &&
          (resting.outlineWidth !== focused.outlineWidth ||
            resting.outlineStyle !== focused.outlineStyle ||
            resting.outlineColor !== focused.outlineColor);

        // A ring drawn with box-shadow is legitimate too — but only if focusing
        // is what drew it. A shadow present at rest is a component's own
        // treatment, not a focus indicator.
        const shadowAppeared = resting.boxShadow !== focused.boxShadow;

        return !(outlineAppeared || shadowAppeared);
      })
      .map((stop) => stop.description);
  });

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
 * Stale as written: this asserted the OLD design's icon-only rail collapse at
 * 1281px (labels going `sr-only` below it). The frontend visual refactor
 * deliberately replaced that with a fixed-width Sidebar (DESIGN_TOKENS.md:
 * "Sidebar | 250px fixed") that is either fully inline with visible text
 * labels (>=1024px) or an entirely hidden drawer (<1024px) — Sidebar.tsx's
 * own doc comment names this explicitly: "replacing the old design's
 * icon-only collapse at 1281px". There is no longer any width, at or above
 * 1024px, where the sidebar is a narrower icon-only column, so every width
 * this test exercises (all >=1024px) is now the single "inline, full labels"
 * regime, and 250px replaces the old 244px measurement.
 *
 * The part of the original intent that still matters is kept: a link's
 * accessible name must not silently change with viewport width. The <1024px
 * hidden-drawer behaviour itself is covered elsewhere (this file's own
 * <768px sweep above, and customers.spec.ts's 900px table/card breakpoint).
 */
test("navigation keeps its accessible names and sidebar width at every breakpoint >=1024px", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  const names: Record<number, string[]> = {};

  for (const width of [1440, 1281, 1280, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    await page.goto(`/t/${tenant.slug}/customers`);

    const nav = page.getByRole("navigation", { name: "Main navigation" });
    await expect(nav).toBeVisible();
    names[width] = await nav.getByRole("link").evaluateAll((links) =>
      links.map((link) => (link.textContent ?? "").trim()),
    );

    const sidebarWidth = await nav
      .locator("xpath=ancestor::aside[1]")
      .evaluate((el) => el.getBoundingClientRect().width);
    expect(sidebarWidth, `sidebar width at ${width}px`).toBe(250);
  }

  expect(names[1280]).toEqual(names[1281]);
  expect(names[1024]).toEqual(names[1440]);
  expect(names[1440]).toContain("Customers");
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
