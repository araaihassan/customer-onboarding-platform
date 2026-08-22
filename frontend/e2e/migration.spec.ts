import { expect, test } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Publish v2, review its migration candidates, and migrate the eligible one.
 *
 * The two workflow versions are seeded through the API, same reason as
 * `case-lifecycle.spec.ts`: authoring itself is `workflow-authoring.spec`'s
 * job, and this file is about the review screen and the migrate action.
 *
 * "Stage renamed, one case already past it" is the shape that actually
 * produces one eligible and one ineligible candidate: `MigrationService`'s
 * only two ineligibility reasons are a passed stage missing from the target
 * version, or a required attribute the case never got a value for
 * (`MigrationService.evaluate`). Adding a stage on its own leaves every
 * existing case's passed stages untouched and so never disqualifies anyone
 * -- the rename is what a case that has already moved past "Verification"
 * cannot come back from, while a case still sitting on "Intake" (which v2
 * keeps under that name) never notices.
 */
let tenant: Tenant;
let customerId: string;
let templateId: string;
let v1VersionId: string;
let v2VersionId: string;
let caseAId: string; // stays on Intake -- eligible
let caseBId: string; // reaches Go Live, having passed Verification -- ineligible in v2

type RoadmapStage = { name?: string; milestones?: { name?: string; requirements?: { id?: string; label?: string; status?: string }[] }[] };

async function satisfyByLabel(admin: Api, caseId: string, label: string) {
  const roadmap = await admin.get<{ stages: RoadmapStage[] }>(`/cases/${caseId}/roadmap`);
  for (const stage of roadmap.stages ?? []) {
    for (const milestone of stage.milestones ?? []) {
      const requirement = (milestone.requirements ?? []).find((r) => r.label === label);
      if (requirement?.id) {
        await admin.post(`/cases/${caseId}/requirements/${requirement.id}/satisfy`, {});
        return;
      }
    }
  }
  throw new Error(`No open requirement labelled "${label}" was found on case ${caseId}`);
}

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "migrate");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer("Acme Renewals");
  customerId = customer.id;

  const template = await admin.createWorkflowTemplate("Renewal Process");
  templateId = template.id;

  const v1 = await admin.createDraftVersion(templateId);
  v1VersionId = v1.versionId;
  await admin.saveDraft(templateId, v1VersionId, {
    stages: [
      {
        key: "intake",
        name: "Intake",
        milestones: [
          { key: "provide-details", name: "Provide details", requirements: [{ kind: "MANUAL", label: "Details submitted", mandatory: true }] },
        ],
      },
      {
        key: "verification",
        name: "Verification",
        milestones: [
          { key: "verify-identity", name: "Verify identity", requirements: [{ kind: "MANUAL", label: "ID verified", mandatory: true }] },
        ],
      },
      {
        key: "go-live",
        name: "Go Live",
        milestones: [
          { key: "activate-account", name: "Activate account", requirements: [{ kind: "MANUAL", label: "Account activated", mandatory: true }] },
        ],
      },
    ],
  });
  await admin.publishVersion(templateId, v1VersionId);

  caseAId = (await admin.createCase(customerId, templateId)).id;
  caseBId = (await admin.createCase(customerId, templateId)).id;
  await satisfyByLabel(admin, caseBId, "Details submitted");
  await satisfyByLabel(admin, caseBId, "ID verified");

  const v2 = await admin.createDraftVersion(templateId);
  v2VersionId = v2.versionId;
  await admin.saveDraft(templateId, v2VersionId, {
    stages: [
      {
        key: "intake",
        name: "Intake",
        milestones: [
          { key: "provide-details", name: "Provide details", requirements: [{ kind: "MANUAL", label: "Details submitted", mandatory: true }] },
        ],
      },
      {
        // Renamed from "Verification" -- the change that disqualifies case B,
        // which has already exited a stage of that name.
        key: "compliance-review",
        name: "Compliance Review",
        milestones: [
          { key: "compliance-check", name: "Compliance check", requirements: [{ kind: "MANUAL", label: "Compliance confirmed", mandatory: true }] },
        ],
      },
      {
        key: "go-live",
        name: "Go Live",
        milestones: [
          { key: "activate-account", name: "Activate account", requirements: [{ kind: "MANUAL", label: "Account activated", mandatory: true }] },
        ],
      },
      {
        // The stage v2 actually adds.
        key: "follow-up",
        name: "Follow-up",
        milestones: [
          { key: "send-welcome-kit", name: "Send welcome kit", requirements: [{ kind: "MANUAL", label: "Kit sent", mandatory: true }] },
        ],
      },
    ],
  });
  await admin.publishVersion(templateId, v2VersionId);

  await request.dispose();
});

test("reviews migration candidates and migrates the eligible case", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/workflows/${templateId}/migration?versionId=${v2VersionId}`);

  await expect(page.getByRole("heading", { name: "Migration review" })).toBeVisible();

  const rowA = page.getByRole("row").filter({ hasText: shortId(caseAId) });
  const rowB = page.getByRole("row").filter({ hasText: shortId(caseBId) });

  await expect(rowA.getByText("Eligible", { exact: true })).toBeVisible();
  await expect(rowB.getByText("Not eligible")).toBeVisible();
  await expect(rowB).toContainText(
    "Stage 'Verification' has already been completed but no longer exists in the new version",
  );

  // The ineligible row's checkbox cannot be checked -- MigrationTable disables it.
  await expect(rowB.getByRole("checkbox")).toBeDisabled();

  await page.getByRole("checkbox", { name: `Select case ${caseAId}` }).check();
  await page.getByRole("button", { name: "Migrate selected" }).click();

  // A migrated case is no longer "on an old version of this target" --
  // MigrationService.preview excludes it, so its row disappears rather than
  // flipping some status column.
  await expect(rowA).toHaveCount(0);
  await expect(rowB).toBeVisible();

  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseAId}`);
  await expect(page.getByText("workflow v2 (frozen)")).toBeVisible();
  await expect(page.getByTestId("milestone-row")).toHaveCount(4);

  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseBId}`);
  await expect(page.getByText("workflow v1 (frozen)")).toBeVisible();
});

/** Mirrors lib/api/customers.ts's own shortId: the last "-"-delimited segment of the id. */
function shortId(id: string): string {
  const parts = id.split("-");
  return parts[parts.length - 1] ?? id;
}
