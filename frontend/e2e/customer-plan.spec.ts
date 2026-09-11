import { expect, test } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Sub-project 3A's first true end-to-end proof (QA Q20-Q24): clone a
 * catalogue template for a customer, tailor the clone, publish it, submit
 * and decide its shape approval (gate 1), open a journey on it and confirm
 * it starts ON_HOLD, issue and decide a schedule revision (gate 2), confirm
 * the hold releases automatically, and confirm the journey's first
 * requirement can now be satisfied.
 *
 * Every piece here (Tasks 1-32) already has unit/vitest coverage in
 * isolation; this is the first time any of it is proven wired together
 * live, in a real browser against a real backend and a real Postgres --
 * the whole reason this spec exists, per its own brief.
 *
 * The catalogue source is seeded through the API (createWorkflowTemplate/
 * createDraftVersion/saveDraft/publishVersion), the exact convention
 * case-lifecycle.spec.ts and tasks.spec.ts already established for a
 * workflow that only needs to exist, not be authored on screen. Cloning,
 * tailoring, publishing the CLONE, both approval gates, and satisfying the
 * requirement are all driven through the real screens -- proving those
 * screens work together live is this spec's entire point.
 *
 * A single admin actor drives the whole arc rather than a hand-built role:
 * the seeded Administrator template already holds every permission this
 * arc touches at ALL (workflow.manage, plan.approve_shape, plan.issue,
 * plan.approve_schedule, case.view, workflow.view, customer.view -- see
 * RoleTemplates.java's own comments on why each is seeded to Administrator
 * regardless of which narrower template also holds it), so there is no
 * "case.view without workflow.view" 404 trap to fall into here.
 */
let tenant: Tenant;
let customerId: string;
const customerName = "Northwind Client";
const catalogueName = "Standard Onboarding";
const cloneName = "Northwind Onboarding Plan";

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "custplan");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer(customerName);
  customerId = customer.id;

  // The catalogue source: one stage, one milestone, one MANUAL requirement --
  // published, so it is cloneable at all (CustomerTemplateService.clone
  // refuses an unpublished source with a 422).
  const { id: templateId } = await admin.createWorkflowTemplate(catalogueName);
  const { versionId, lockVersion } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "onboarding",
        name: "Onboarding",
        autoAdvance: true,
        // StageRequest.portalVisible is a primitive boolean (unlike
        // MilestoneRequest's own deliberately-boxed Boolean) -- a client that
        // omits it binds to Jackson's false, hiding every milestone under
        // this stage from the Plan tab's issue-preview regardless of the
        // milestone's own flag (PortalVisibilityTest's class doc names this
        // exact, documented, not-yet-fixed gap). Sent explicitly here for
        // the same reason autoAdvance already has to be.
        portalVisible: true,
        milestones: [
          {
            key: "kickoff",
            name: "Kickoff",
            estimatedDurationDays: 2,
            dependsOnMilestoneKeys: [],
            requirements: [{ kind: "MANUAL", label: "Sign onboarding agreement", mandatory: true }],
          },
        ],
        branchRules: [],
      },
    ],
    attributes: [],
    lockVersion,
  });
  await admin.publishVersion(templateId, versionId);

  await request.dispose();
});

test("clone, tailor, publish, both plan gates, hold release, and satisfying the first requirement", async ({
  page,
}) => {
  await signIn(page, tenant.slug, tenant.adminEmail);

  // --- Clone the catalogue template for this customer (QA Q21) ---
  await page.goto(`/t/${tenant.slug}/admin/workflows`);
  await page
    .locator("li", { hasText: catalogueName })
    .getByRole("button", { name: "Clone for customer" })
    .click();

  const cloneDialog = page.getByRole("dialog", { name: new RegExp(catalogueName) });
  await cloneDialog.getByLabel("Name").fill(cloneName);
  await cloneDialog.getByLabel("Search customers").fill(customerName);
  // The customer picker's <select> is populated from a debounced (250ms)
  // search -- wait for the real option to land before selecting it, rather
  // than racing selectOption against a still-empty list.
  await expect(cloneDialog.locator("#clone-customer-select")).toContainText(customerName);
  // exact: true -- an unqualified match also catches "Search customers"
  // (Playwright's getByLabel is a case-insensitive substring match by
  // default, and "Customer" is a substring of "customers").
  await cloneDialog.getByLabel("Customer", { exact: true }).selectOption({ label: customerName });
  await cloneDialog.getByRole("button", { name: "Clone template" }).click();
  await expect(cloneDialog).toBeHidden();

  // The clone is a fresh DRAFT the moment it is created (Task 16) -- so
  // "Start editing" collides with that already-open draft
  // (WorkflowService.createDraft refuses a second one, 409), and the list
  // screen's own "Resume editing" affordance on that conflict is how a
  // freshly cloned template's draft is actually reached (Task 30's own
  // report names this exact sequence, not a workaround invented here).
  await page
    .locator("li", { hasText: cloneName })
    .getByRole("button", { name: "Start editing" })
    .click();
  await page.getByRole("button", { name: "Resume editing" }).click();
  await page.waitForURL(/\/admin\/workflows\/[0-9a-f-]+\/versions\/[0-9a-f-]+$/);

  // --- Tailor the clone (QA Q21: an edit that never reaches the source) ---
  await page.getByRole("button", { name: /Onboarding/ }).click();
  // exact: true -- an unqualified match also catches the stage's own
  // "SLA (days)" field (getByLabel is case-insensitive substring by default).
  await page.getByLabel("Days", { exact: true }).fill("5");
  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("button", { name: "Publish version" })).toBeEnabled();

  await page.getByRole("button", { name: "Publish version" }).click();
  await expect(page.getByRole("heading", { name: "Shape approval" })).toBeVisible();

  // --- Gate 1: shape approval, submitted and decided (QA Q22) ---
  await expect(page.getByText("Not yet submitted for shape approval.")).toBeVisible();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  // Decide controls only render once SUBMITTED, and only to a
  // plan.approve_shape holder -- their appearance is itself the proof of
  // the state transition, not just a stepping stone to click through.
  await expect(page.getByRole("button", { name: "Approve" })).toBeVisible();

  await page.getByLabel("Decision note").fill("Shape matches what we discussed with the sponsor.");
  await page.getByRole("button", { name: "Approve" }).click();
  // Once decided, gate 1 is one-shot: neither the decide controls nor a
  // resubmit affordance remain (approval.status is now APPROVED, not
  // absent/REJECTED).
  await expect(page.getByRole("button", { name: "Approve" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Submit for approval" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Resubmit for approval" })).toHaveCount(0);

  // --- Open a journey on the customer template: it must start held (Q22/Q23) ---
  await page.goto(`/t/${tenant.slug}/customers/${customerId}`);
  await page.getByRole("button", { name: "New case" }).click();
  const caseDialog = page.getByRole("dialog", { name: "New case" });
  await caseDialog.getByLabel("Case name").fill("Northwind Kickoff");
  await caseDialog.getByRole("radio", { name: cloneName }).click();
  await caseDialog.getByRole("button", { name: "Create case" }).click();

  await page.waitForURL(/\/cases\/[0-9a-f-]+$/);
  const caseId = page.url().split("/cases/")[1]!;

  await expect(page.getByTestId("case-fact-grid").getByText("On hold", { exact: true })).toBeVisible();
  const banner = page.getByTestId(`awaiting-approval-banner-${caseId}`);
  await expect(banner).toBeVisible();
  await expect(banner.getByText(/Issue a schedule revision to get things started/)).toBeVisible();

  // --- Gate 2: issue and decide the case's first schedule revision (Q22/Q23) ---
  await page.getByRole("tab", { name: "Plan" }).click();
  const plan = page.getByRole("tabpanel");
  // Scoped to the tabpanel: the held-case banner above it carries an
  // identically worded "Issue a schedule revision" button of its own, so an
  // unscoped locator here would be ambiguous.
  await plan.getByRole("button", { name: "Issue a schedule revision" }).click();

  const issueDialog = page.getByRole("dialog", { name: "Issue a schedule revision" });
  // The preview lists exactly the portal-visible milestones that will be
  // snapshotted -- Kickoff is portalVisible by default (never set false).
  await expect(issueDialog.getByText("Kickoff")).toBeVisible();
  await issueDialog.getByLabel("Note (optional)").fill("First cut of the schedule.");
  await issueDialog.getByRole("button", { name: "Issue" }).click();
  await expect(issueDialog).toBeHidden();

  await expect(plan.getByText("Revision 1")).toBeVisible();
  await plan.getByLabel("Decision note").fill("Approved by the sponsor over email.");
  await plan.getByRole("button", { name: "Approve" }).click();

  // --- The hold releases automatically on the first approved revision ---
  await expect(banner).toBeHidden();
  await expect(page.getByTestId("case-fact-grid").getByText("Active", { exact: true })).toBeVisible();

  // --- And the journey's first requirement can now be satisfied ---
  await page.getByRole("tab", { name: "Journey" }).click();
  const row = page.getByTestId("milestone-row").filter({ hasText: "Kickoff" });
  await row.getByRole("button", { name: /Kickoff/ }).click();
  const checkbox = row.getByRole("checkbox");
  // Not .check(): the checkbox waits for the server round trip before
  // flipping (the same "real and local" departure case-lifecycle.spec.ts
  // and tasks.spec.ts both already document) -- a plain click plus an
  // auto-retrying expect is what actually waits for it.
  await checkbox.click();
  await expect(checkbox).toBeChecked();
});
