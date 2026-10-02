import { expect, test } from "@playwright/test";
import type { Locator, Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { activate, Api, apiContext, PASSWORD, provisionTenant, readEmailToken, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Sub-project 5, Task 28: the agreement lifecycle, live, through the real UI.
 *
 * Roles. The seeded Project Manager template deliberately holds no
 * `agreement.sign_record`, and Account Manager lacks the rest of what the flow needs, so the
 * "manager" half (draft, submit, send, record, cancel) is the tenant Administrator, who holds the
 * whole catalogue. The reviewer is a real user holding the seeded **Legal** role template
 * (`agreement.review` at ALL, plus `workflow.view`, `case.view`, `customer.view`), so the four-eyes
 * step is a genuinely different person from the submitter and last editor.
 *
 * Seed payload: every field that NPEs or misbinds when omitted is spelled out (CLAUDE.md "Live-running
 * the three new specs"), and `lockVersion` is taken from the create response, never assumed.
 *
 * The three tests share one tenant and run serially: the lifecycle screen at the end counts what
 * the first two left behind.
 */
test.describe.configure({ mode: "serial" });

let tenant: Tenant;
let customerId: string;
let templateId: string;
let legalEmail: string;

const CONTACT_NAME = "Carol Contact";
const LEGAL_NAME = "Lena Legal";
const AGREEMENT_NAME = "Master Services Agreement";

// A minimal but real PDF header: the upload path sniffs the bytes, never the declared type.
const PDF = Buffer.from(
  "%PDF-1.4\n%âãÏÓ\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n",
  "latin1",
);
const pdf = (name: string) => ({ name, mimeType: "application/pdf", buffer: PDF });

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "agr");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  customerId = (await admin.createCustomer("Orbit Freight")).id;
  await admin.createContact(customerId, CONTACT_NAME, `carol@${tenant.slug}.test`);

  const { id } = await admin.createWorkflowTemplate("Contracting");
  templateId = id;
  const { versionId, lockVersion } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "agreement",
        name: "Agreement",
        autoAdvance: true,
        milestones: [
          {
            key: "contract",
            name: "Contract",
            estimatedDurationDays: 3,
            dependsOnMilestoneKeys: [],
            requirements: [
              {
                kind: "SIGNATURE",
                label: AGREEMENT_NAME,
                weight: 1,
                mandatory: true,
                agreementRecordMode: "FILE_BACKED",
                agreementName: AGREEMENT_NAME,
              },
            ],
          },
        ],
        branchRules: [],
      },
    ],
    attributes: [],
    lockVersion,
  });
  await admin.publishVersion(templateId, versionId);

  // A real Legal user from the seeded template -- never a hand-built role.
  legalEmail = `legal@${tenant.slug}.test`;
  const { id: legalId } = await admin.createUser(legalEmail, LEGAL_NAME);
  const roles = await admin.get<{ id: string; name: string }[]>("/admin/roles");
  const legalRole = roles.find((r) => r.name === "Legal");
  expect(legalRole, "the seeded Legal role template").toBeTruthy();
  await admin.assignRole(legalId, legalRole!.id);
  await activate(request, tenant.slug, await readEmailToken(legalEmail), PASSWORD);

  await request.dispose();
});

function casePath(caseId: string, query = "tab=agreements") {
  return `/t/${tenant.slug}/customers/${customerId}/cases/${caseId}?${query}`;
}

function detailRegion(page: Page): Locator {
  return page.getByRole("region", { name: "Agreement detail" });
}

async function openCase(request: Parameters<typeof Api.as>[0], name: string) {
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const { id } = await admin.createCase(customerId, templateId, name);
  return id;
}

/** Opens the (only live) agreement row on the Agreements tab and waits for its detail panel. */
async function openAgreement(page: Page, caseId: string) {
  await page.goto(casePath(caseId));
  await page.getByRole("button", { name: "Open", exact: true }).first().click();
  await expect(detailRegion(page).getByRole("heading", { level: 3, name: AGREEMENT_NAME })).toBeVisible();
}

/** Upload a PDF, add a contact and an internal signatory, and save -- each step waits for its round trip. */
async function prepareDraft(page: Page) {
  const region = detailRegion(page);

  // File first: an upload bumps lockVersion and remounts both forms, which would discard unsaved signatory edits.
  await region.getByLabel("Upload file").setInputFiles(pdf("msa.pdf"));
  await expect(region.getByText("A file is attached to this draft.")).toBeVisible();

  await region.getByLabel("Person").selectOption({ label: CONTACT_NAME });
  await region.getByLabel("Role on this agreement").fill("Customer signatory");
  await region.getByRole("button", { name: "Add signatory" }).click();

  await region.getByLabel("Signatory type").selectOption("INTERNAL");
  await region.getByLabel("Person").selectOption({ label: LEGAL_NAME });
  await region.getByLabel("Role on this agreement").fill("Company signatory");
  await region.getByRole("button", { name: "Add signatory" }).click();

  await expect(region.getByTestId("signatory-name")).toHaveText([CONTACT_NAME, LEGAL_NAME]);
  // The button is also disabled while the request is in flight, so "disabled" alone proves nothing:
  // wait for the PUT itself to land before moving on (submitting earlier races the save).
  const saved = page.waitForResponse((r) => r.request().method() === "PUT" && r.url().includes("/signatories"));
  await region.getByRole("button", { name: "Save signatories" }).click();
  expect((await saved).status()).toBe(200);
  await expect(region.getByRole("button", { name: "Save signatories" })).toBeDisabled();
  await expect(region.getByTestId("signatory-name")).toHaveText([CONTACT_NAME, LEGAL_NAME]);
}

async function submitForReview(page: Page) {
  const region = detailRegion(page);
  await region.getByRole("button", { name: "Submit for review" }).click();
  await expect(region.getByText("Under review", { exact: true }).first()).toBeVisible();
}

test("the full arc: draft, submit, four-eyes approval, send, two recorded signatures, signed and the milestone complete", async ({
  page,
  browser,
  request,
}) => {
  const caseId = await openCase(request, "Orbit MSA");

  await signIn(page, tenant.slug, tenant.adminEmail);
  await openAgreement(page, caseId);
  const agreementId = (await detailRegion(page).getAttribute("data-agreement-id"))!;
  await prepareDraft(page);
  await submitForReview(page);

  // Four-eyes: the submitter sees Approve, but disabled, with the reason.
  const region = detailRegion(page);
  await expect(region.getByRole("button", { name: "Approve" })).toBeDisabled();
  await expect(region.getByText("You submitted this version, so someone else must review it")).toBeVisible();

  // A different person -- a real Legal user -- approves.
  const legalContext = await browser.newContext({ baseURL: BASE_URL });
  const legalPage = await legalContext.newPage();
  await signIn(legalPage, tenant.slug, legalEmail);
  await legalPage.goto(casePath(caseId, `tab=agreements&agreement=${agreementId}`));
  const legalRegion = detailRegion(legalPage);
  await expect(legalRegion.getByRole("heading", { level: 3, name: AGREEMENT_NAME })).toBeVisible();
  await expect(legalRegion.getByRole("button", { name: "Approve" })).toBeEnabled();
  await legalRegion.getByRole("button", { name: "Approve" }).click();
  await expect(legalRegion.getByText("Approved", { exact: true }).first()).toBeVisible();
  await legalContext.close();

  // Back as the manager: send, then record both signatures.
  await page.reload();
  const manager = detailRegion(page);
  await expect(manager.getByRole("button", { name: "Send for signature" })).toBeVisible();
  await manager.getByRole("button", { name: "Send for signature" }).click();
  await expect(manager.getByText("Sent", { exact: true }).first()).toBeVisible();

  // First signature: the contact. Not the last, so no file is asked for.
  await manager.getByRole("button", { name: "Record signature" }).click();
  let dialog = page.getByRole("dialog", { name: "Record a signature" });
  await dialog.getByLabel("Signatory").selectOption({ label: `${CONTACT_NAME} (Customer signatory)` });
  await dialog.getByLabel("Method").fill("Wet ink");
  await dialog.getByRole("button", { name: "Record signature" }).click();
  await expect(dialog).toBeHidden();
  await expect(manager.getByText("Awaiting signature", { exact: true }).first()).toBeVisible();

  // Last signature: the internal signatory, with the countersigned PDF.
  await manager.getByRole("button", { name: "Record signature" }).click();
  dialog = page.getByRole("dialog", { name: "Record a signature" });
  await dialog.getByLabel("Method").fill("Emailed PDF");
  await dialog.getByLabel("Countersigned file").setInputFiles(pdf("msa-countersigned.pdf"));
  await dialog.getByRole("button", { name: "Record signature" }).click();
  await expect(dialog).toBeHidden();
  await expect(manager.getByText("Signed", { exact: true }).first()).toBeVisible();

  // The requirement is satisfied by the agreement: milestone done, progress at 100%.
  await page.goto(casePath(caseId, "tab=journey"));
  await expect(
    page.getByTestId("milestone-row").filter({ hasText: "Contract" }).getByText("Done", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText("100%", { exact: true }).first()).toBeVisible();
});

test("cancel and replace: a successor draft appears, the old row is under Replaced, and the requirement stays open", async ({
  page,
  request,
}) => {
  const caseId = await openCase(request, "Orbit MSA (replaced)");

  await signIn(page, tenant.slug, tenant.adminEmail);
  await openAgreement(page, caseId);
  await prepareDraft(page);
  await submitForReview(page);

  const region = detailRegion(page);
  await region.getByRole("button", { name: "Cancel and replace" }).click();
  const dialog = page.getByRole("dialog", { name: "Cancel and replace this agreement" });
  await dialog.getByLabel("Reason for cancelling").fill("Wrong counterparty entity");
  await dialog.getByRole("button", { name: "Cancel agreement" }).click();
  await expect(dialog).toBeHidden();

  // The successor draft opens in the panel and sits in the live list; the cancelled one is collapsed.
  await expect(region.getByText("Draft", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("Replaced (1)")).toBeVisible();
  await page.getByText("Replaced (1)").click();
  await expect(page.getByText("Cancelled", { exact: true }).first()).toBeVisible();

  // Cancelling satisfied nothing: the requirement is still open on the roadmap.
  await page.goto(casePath(caseId, "tab=journey"));
  const row = page.getByTestId("milestone-row").filter({ hasText: "Contract" });
  await expect(row.getByText("Done", { exact: true })).toHaveCount(0);
  await expect(row.getByText("Active", { exact: true })).toBeVisible();
  await row.getByRole("button", { name: /Contract/ }).click();
  await expect(row.getByText("Open", { exact: true })).toBeVisible();
});

test("the lifecycle screen counts what the first two tests left behind", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/agreements`);

  await expect(page.getByText("AGREEMENT LIFECYCLE · MANUAL SIGNING")).toBeVisible();

  const count = async (key: string) => Number((await page.getByTestId(`lifecycle-${key}`).locator("p").first().textContent()) ?? "0");
  await expect.poll(() => count("signed")).toBeGreaterThanOrEqual(1);
  await expect.poll(() => count("draft")).toBeGreaterThanOrEqual(1);
});
