import { expect, test } from "@playwright/test";
import { Api, apiContext, provisionTenant, readEmail, seedUser, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Sub-project 6 end to end (spec 10.3): a breach and its escalation reaching the
 * owner's manager, a customer wait pausing the clock and a reminder going out, and
 * the builder's "Pause on customer" toggle surviving a save.
 *
 * **This spec moves the backend's clock.** `POST /dev/clock/offset` shifts "now"
 * for the whole backend process, for every tenant, and nothing ever shifts it back.
 * So every spec that runs after this one in the same Playwright invocation, against
 * the same backend, sees a clock about two weeks ahead. That is harmless for the
 * existing specs: none asserts an absolute date against "now" (a grep of `e2e/` for
 * `new Date(` / `Date.now(` finds only the polling deadlines in `support/tenant.ts`),
 * access tokens and refresh cookies are not computed from the offset clock, and a
 * spec's own tenant is brand new, so nothing of another spec's is judged overdue
 * against it. The tests here therefore run in the order written (serial mode), and
 * the second test deliberately creates its case AFTER the shift so its clock is
 * young.
 *
 * The shift also reaches CONCURRENT workers (the shift is process-wide, not per spec,
 * so another worker's request mid-run sees a clock days ahead) and the backend's own
 * five-minute scheduled sweep, which will then escalate other tenants' overdue work
 * against the shifted clock. That is harmless today: the audit events it writes are not
 * timeline-visible and no notification UI exists yet (6B). `reuseExistingServer` means
 * a dev backend started by hand and reused here stays shifted until it is restarted.
 *
 * Shift sizes are chosen to hold on any weekday: four calendar days always contain
 * at least one business day (Mon-Fri), and a further ten always contain at least
 * five, which clears the policy's escalate-after threshold whatever day this runs.
 *
 * Seeded through the API rather than the builder: the workflow, because
 * `slaDays`/`pausesOnCustomer`/`autoAdvance` are sent explicitly (an omitted
 * boolean binds to false, not the builder's own default) and test 3 is the one that
 * drives the builder.
 */
test.describe.configure({ mode: "serial" });

let tenant: Tenant;
let customerId: string;
let templateId: string;
let contactEmail: string;
let contactId: string;
let ownerId: string;
let bossEmail: string;
let breachedCaseId: string;
const BREACH_CASE = "SLA breach fixture";
const WAIT_CASE = "SLA customer wait fixture";

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "sla");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  customerId = (await admin.createCustomer("Brightwater Freight")).id;
  contactEmail = `contact@${tenant.slug}.test`;
  contactId = (await admin.createContact(customerId, "Casey Contact", contactEmail)).id;

  const ownerEmail = await seedUser(request, admin, tenant, "owner", { "case.view": "ASSIGNED" });
  bossEmail = await seedUser(request, admin, tenant, "boss", { "case.view": "ASSIGNED" });
  const users = await admin.get<{ content: { id: string; email: string }[] }>("/admin/users?size=100");
  const idOf = (email: string) => users.content.find((u) => u.email === email)!.id;
  ownerId = idOf(ownerEmail);
  await admin.setManager(ownerId, idOf(bossEmail));

  const { id } = await admin.createWorkflowTemplate("SLA onboarding");
  templateId = id;
  const { versionId, lockVersion } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "onboarding",
        name: "Onboarding",
        autoAdvance: true,
        slaDays: 1,
        pausesOnCustomer: true,
        milestones: [
          {
            key: "kickoff",
            name: "Kickoff",
            estimatedDurationDays: 1,
            dependsOnMilestoneKeys: [],
            requirements: [{ kind: "MANUAL", label: "Confirm kickoff", weight: 1, mandatory: true }],
          },
        ],
        branchRules: [],
      },
    ],
    attributes: [],
    lockVersion,
  });
  await admin.publishVersion(templateId, versionId);

  // The breach case, owned by `owner` (a full-replace PUT, so read every field first).
  breachedCaseId = (await admin.createCase(customerId, templateId, BREACH_CASE)).id;
  const current = await admin.get<{
    name: string;
    owningDepartmentId?: string;
    owningTeamId?: string;
    attributes?: Record<string, string>;
  }>(`/cases/${breachedCaseId}`);
  await admin.put(`/cases/${breachedCaseId}`, {
    name: current.name,
    ownerUserId: ownerId,
    owningDepartmentId: current.owningDepartmentId ?? null,
    owningTeamId: current.owningTeamId ?? null,
    attributes: current.attributes ?? {},
  });

  await request.dispose();
});

test("a breached clock escalates to the owner's manager, by email and on the war room", async ({ page, request }) => {
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  // Order matters: the first shift (T+4d) must be small enough that the breach is stamped but
  // not yet overdue by the policy threshold, so the second shift (T+14d total) is what raises
  // the escalation. Reordering or merging the shifts breaks the "repeat is quiet" assertion.
  await admin.shiftClock(4 * 86_400);
  await admin.runSlaSweep(); // stamps the breach
  await admin.runSlaSweep(); // nothing new yet; proves a repeat is quiet
  await admin.shiftClock(10 * 86_400);
  await admin.runSlaSweep(); // the breach is now overdue by more than the policy threshold

  const email = await readEmail(bossEmail, "Escalation:");
  expect(email).toContain("Escalation:");

  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/sla`);

  const breached = page.locator("section", { has: page.getByRole("heading", { name: /^Breached/ }) });
  const card = breached.getByTestId("war-room-card").filter({ hasText: BREACH_CASE });
  await expect(card).toBeVisible();
  await expect(card.getByTestId("sla-chip")).toContainText("BREACHED");
  await expect(card.getByTestId("war-room-note")).toContainText("boss");
});

test("an open document request pauses the clock, and a customer can be reminded once a day", async ({
  page,
  request,
}) => {
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const { id: caseId } = await admin.createCase(customerId, templateId, WAIT_CASE);
  await admin.post(
    `/cases/${caseId}/document-requests`,
    { category: "OTHER", description: "Certificate of incorporation", requiresReview: false, requestedOfContactId: contactId },
    201,
  );

  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`);
  await expect(page.getByTestId("sla-chip").first()).toContainText("SLA PAUSED");

  await page.goto(`/t/${tenant.slug}/sla`);
  const card = page.getByTestId("war-room-card").filter({ hasText: WAIT_CASE });
  await expect(card).toBeVisible();
  await card.getByRole("button", { name: "Remind customer" }).click();

  const dialog = page.getByRole("dialog", { name: "Remind customer" });
  await dialog.getByRole("button", { name: "Remind", exact: true }).click();
  await expect(dialog.getByText(/Reminded 1×/)).toBeVisible();

  await dialog.getByRole("button", { name: "Remind", exact: true }).click();
  await expect(dialog.getByText("Already reminded in the last 24 hours.")).toBeVisible();

  // 6B: a customer reminder is queued in the email outbox in the request's own transaction and
  // leaves through the dispatcher (every minute in production), no longer inline. Run it now.
  await admin.runEmailDispatch();
  const reminder = await readEmail(contactEmail, "Reminder:");
  expect(reminder).toContain("Reminder:");
});

test("the builder's Pause on customer toggle defaults on and survives a save", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/workflows`);

  await page.getByRole("button", { name: "New template" }).click();
  const create = page.getByRole("dialog", { name: "New workflow template" });
  await create.getByLabel("Name").fill("Pause toggle");
  await create.getByRole("button", { name: "Create template" }).click();
  await expect(page.getByRole("heading", { name: "Edit workflow" })).toBeVisible();

  await page.getByRole("button", { name: "Add stage" }).click();
  await page.getByLabel("Stage name").fill("Intake");

  const toggle = () => page.getByRole("switch", { name: "Pause on customer" });
  await expect(toggle()).toHaveAttribute("aria-checked", "true");
  await toggle().click();
  await expect(toggle()).toHaveAttribute("aria-checked", "false");

  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("button", { name: "Publish version" })).toBeEnabled();

  await page.reload();
  await page.getByRole("button", { name: /Intake/ }).click();
  await expect(toggle()).toHaveAttribute("aria-checked", "false");
});
