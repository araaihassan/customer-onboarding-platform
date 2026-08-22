import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { Api, apiContext, provisionTenant, seedUser, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * The engine behaviours no unit test can prove are wired to the screens: a
 * branch that skips a stage, requirement satisfaction driving an automatic
 * advance, a force-complete the requester cannot approve, and a case
 * reaching exactly 100% with a skipped stage in it.
 *
 * The workflow is seeded through the API, not the builder: it needs a
 * declared `segment` attribute and a stage `entryCondition`, and the builder
 * has UI for neither (recorded in CLAUDE.md and in workflow-authoring.spec's
 * own comment) -- `draftState`'s `addAttribute` and a stage's
 * `entryCondition` both exist only as data, with nothing in the page to set
 * them. `workflow-authoring.spec.ts` covers what the builder CAN author.
 */
let tenant: Tenant;
let customerId: string;
let templateId: string;
let pmEmail: string;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "lifecycle");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer("Northwind Traders");
  customerId = customer.id;

  const { id } = await admin.createWorkflowTemplate("Client Onboarding");
  templateId = id;
  const { versionId } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "registration",
        name: "Registration",
        milestones: [
          { key: "sign-up", name: "Sign up", requirements: [{ kind: "MANUAL", label: "ID check", mandatory: true }] },
        ],
      },
      {
        key: "legal-review",
        name: "Legal Review",
        // Entered only for an ENTERPRISE case -- an SMB case skips it
        // entirely (spec 5.3 step 3), which is the branch this spec exists
        // to prove reaches the screen.
        entryCondition: { source: "ATTRIBUTE", key: "segment", operator: "EQ", value: "ENTERPRISE" },
        milestones: [
          {
            key: "review-contract",
            name: "Review contract",
            requirements: [{ kind: "MANUAL", label: "Legal sign-off", mandatory: true }],
          },
        ],
      },
      {
        key: "go-live",
        name: "Go Live",
        milestones: [
          {
            key: "finish-onboarding",
            name: "Finish onboarding",
            requirements: [{ kind: "MANUAL", label: "Final review", mandatory: true }],
          },
        ],
      },
    ],
    attributes: [
      { key: "segment", label: "Segment", dataType: "ENUM", required: true, allowedValues: ["ENTERPRISE", "SMB"] },
    ],
  });
  await admin.publishVersion(templateId, versionId);

  // Holds both halves of the force-complete flow -- request AND decide --
  // so the only reason the decide attempt below fails is self-approval,
  // never a missing grant. Spec 9.2's own negative test makes the same
  // choice, for the same reason: proving the RIGHT rule refused it.
  pmEmail = await seedUser(request, admin, tenant, "pm", {
    "case.view": "ALL",
    "milestone.force_complete": "ALL",
    "milestone.force_approve": "ALL",
  });

  await request.dispose();
});

async function createCase(page: Page, segment: "ENTERPRISE" | "SMB"): Promise<string> {
  await page.goto(`/t/${tenant.slug}/customers/${customerId}`);
  await page.getByRole("button", { name: "New case" }).click();

  const dialog = page.getByRole("dialog", { name: "New case" });
  await dialog.getByRole("radio", { name: "Client Onboarding" }).click();
  await dialog.getByLabel("Segment", { exact: false }).selectOption(segment);
  await dialog.getByRole("button", { name: "Create case" }).click();

  await page.waitForURL(/\/cases\/[0-9a-f-]+$/);
  return page.url().split("/cases/")[1]!;
}

function milestoneRow(page: Page, milestoneName: string) {
  return page.getByTestId("milestone-row").filter({ hasText: milestoneName });
}

async function expandAndSatisfy(page: Page, milestoneName: string) {
  const row = milestoneRow(page, milestoneName);
  await row.getByRole("button", { name: new RegExp(milestoneName) }).click();
  const checkbox = row.getByRole("checkbox");
  await checkbox.check();
  await expect(checkbox).toBeChecked();
}

test("an ENTERPRISE case enters Legal Review; satisfying Registration advances the roadmap", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await createCase(page, "ENTERPRISE");

  await expect(milestoneRow(page, "Sign up").getByText("Active", { exact: true })).toBeVisible();
  await expect(milestoneRow(page, "Review contract").getByText("Pending", { exact: true })).toBeVisible();

  await expandAndSatisfy(page, "Sign up");

  // The stage exited automatically (auto-advance is the builder's own
  // default) and the entry condition matched -- Legal Review is now current.
  await expect(milestoneRow(page, "Sign up").getByText("Done", { exact: true })).toBeVisible();
  await expect(milestoneRow(page, "Review contract").getByText("Active", { exact: true })).toBeVisible();
});

test("an SMB case skips Legal Review, and a force-complete only the administrator may decide drives it to 100%", async ({
  page,
}) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  const caseId = await createCase(page, "SMB");

  await expandAndSatisfy(page, "Sign up");

  // Legal Review's entry condition evaluates false for SMB -- its milestone
  // is SKIPPED, not merely un-entered, and the case has already moved on to
  // Go Live in the same round trip.
  await expect(milestoneRow(page, "Review contract").getByText("Skipped", { exact: true })).toBeVisible();
  await expect(milestoneRow(page, "Finish onboarding").getByText("Active", { exact: true })).toBeVisible();

  // Sign out and continue as the PM: requests a forced completion, then
  // tries to decide their own request.
  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn(page, tenant.slug, pmEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`);

  const goLive = milestoneRow(page, "Finish onboarding");
  await goLive.getByRole("button", { name: /Finish onboarding/ }).click();
  await goLive.getByRole("button", { name: "Force complete" }).click();

  const forceDialog = page.getByRole("dialog", { name: "Force complete milestone" });
  await forceDialog.getByLabel("Reason").fill("Customer confirmed everything by phone");
  await forceDialog.getByRole("button", { name: "Request" }).click();
  await expect(forceDialog).toBeHidden();

  // The PM holds milestone.force_approve, so the buttons are there -- and
  // the decide call still 403s, because the PM is the requester. Refused,
  // not merely hidden: the milestone still is not DONE.
  await expect(goLive.getByText("Pending approval")).toBeVisible();
  await goLive.getByRole("button", { name: "Approve" }).click();
  await expect(goLive.getByRole("alert")).toBeVisible();
  await expect(goLive.getByText("Done", { exact: true })).toHaveCount(0);

  await page.getByRole("button", { name: "Sign out" }).click();
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`);

  const goLiveAsAdmin = milestoneRow(page, "Finish onboarding");
  await goLiveAsAdmin.getByRole("button", { name: /Finish onboarding/ }).click();
  await goLiveAsAdmin.getByRole("button", { name: "Approve" }).click();

  await expect(goLiveAsAdmin.getByText("Done", { exact: true })).toBeVisible();
  // Skipped milestones leave the progress calculation entirely (spec
  // invariant 10) -- two real milestones, both DONE, is exactly 100%, not
  // two-thirds. `.first()`: the percentage and the status word each appear
  // in more than one place on this page (the header's own pill and fact,
  // for instance), and this assertion only needs one of them to be right.
  await expect(page.getByText("100%").first()).toBeVisible();
  await expect(page.getByText("Completed", { exact: true }).first()).toBeVisible();
});
