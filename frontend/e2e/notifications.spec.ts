import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { Api, apiContext, countEmails, provisionTenant, readEmailBody, seedUser, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Sub-project 6B end to end: the Inbox badge and drawer, the keyboard shortcut,
 * per-type preferences, a keyed stage sending an alert (the template authored
 * through Administration -> Notifications), and a daily digest email.
 *
 * **The last test moves the backend's clock.** `POST /dev/clock/offset` shifts
 * "now" for the whole backend process, for every tenant, and nothing ever shifts
 * it back. So every spec that runs after this one in the same Playwright
 * invocation, against the same backend, sees a clock days ahead. That is
 * harmless for the existing specs: none asserts an absolute date against "now"
 * (a grep of `e2e/` for `new Date(` / `Date.now(` finds only polling deadlines),
 * access tokens and refresh cookies are not computed from the offset clock, and a
 * spec's own tenant is brand new, so nothing of another spec's is judged overdue
 * against it. The tests here therefore run in the order written (serial mode),
 * and the digest test, which shifts, is deliberately LAST in this file.
 *
 * The shift also reaches CONCURRENT workers (the shift is process-wide, not per
 * spec, so another worker's request mid-run sees a clock ahead) and the backend's
 * own scheduled sweeps, which will then escalate and notify about other tenants'
 * overdue work against the shifted clock. `reuseExistingServer` means a dev
 * backend started by hand and reused here stays shifted until it is restarted.
 *
 * The digest test computes its shift from the backend's own current offset (a
 * one-second nudge returns it), so it holds whatever earlier specs already moved.
 * The digest email's links are absolute because `e2e/support/backend.mjs` sets
 * `APP_PUBLIC_BASE_URL` to `http://localhost:3000`.
 */
test.describe.configure({ mode: "serial" });

let tenant: Tenant;
let customerId: string;
let templateId: string;
let caseId: string;
let milestoneId: string;
let annEmail: string;
let benEmail: string;
let benId: string;
const CUSTOMER = "Brightwater Freight";

interface RoadmapView {
  stages: { milestones: { id: string; name: string; requirements: { id: string }[] }[] }[];
}

const stageDef = (key: string, name: string, extra: Record<string, unknown> = {}) => ({
  key,
  name,
  autoAdvance: true,
  branchRules: [],
  milestones: [
    {
      key: `${key}-m`,
      name: `${name} milestone`,
      estimatedDurationDays: 1,
      dependsOnMilestoneKeys: [],
      requirements: [{ kind: "MANUAL", label: `${name} check`, weight: 1, mandatory: true }],
    },
  ],
  ...extra,
});

async function publishWorkflow(admin: Api, name: string, stages: unknown[]): Promise<string> {
  const { id } = await admin.createWorkflowTemplate(name);
  const { versionId, lockVersion } = await admin.createDraftVersion(id);
  await admin.saveDraft(id, versionId, { stages, attributes: [], lockVersion });
  await admin.publishVersion(id, versionId);
  return id;
}

/** ann (task.manage) assigns a new task to ben, through the API. */
async function assignTaskToBen(ann: Api, title: string) {
  await ann.post(`/cases/${caseId}/tasks`, { milestoneId, title, priority: "MEDIUM", assigneeId: benId }, 201);
}

/** Waits until the server holds ben's TASK_ASSIGNED channels as given (the switches are optimistic). */
async function expectTaskAssignedChannels(
  request: Parameters<typeof Api.as>[0],
  inApp: boolean,
  email: boolean,
  cadence?: "IMMEDIATE" | "DAILY" | "WEEKLY",
) {
  const ben = await Api.as(request, tenant.slug, benEmail);
  await expect
    .poll(async () => {
      const prefs = await ben.get<{ emailCadence: string; types: { type: string; inApp: boolean; email: boolean }[] }>("/notifications/preferences");
      const row = prefs.types.find((tp) => tp.type === "TASK_ASSIGNED")!;
      return [row.inApp, row.email, cadence === undefined ? undefined : prefs.emailCadence];
    })
    .toEqual([inApp, email, cadence]);
}

const inboxButton = (page: Page, name: string | RegExp) => page.getByRole("button", { name });
const drawer = (page: Page) => page.getByRole("dialog", { name: "Inbox" });

/** The badge polls every 60 s: reload once rather than wait for it. */
async function reloadToDashboard(page: Page) {
  await page.goto(`/t/${tenant.slug}/dashboard`);
}

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "notif");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  customerId = (await admin.createCustomer(CUSTOMER)).id;
  annEmail = await seedUser(request, admin, tenant, "ann", {
    "case.view": "ALL",
    "task.view": "ALL",
    "task.manage": "ALL",
    "workflow.view": "ALL",
    // TaskService.resolveAssignee reads the assignee under user.view: without it, assigning a
    // task to ben is a 404 (the brief's grant list omitted it).
    "user.view": "ALL",
  });
  benEmail = await seedUser(request, admin, tenant, "ben", { "case.view": "ALL", "task.view": "ALL" });
  const users = await admin.get<{ content: { id: string; email: string }[] }>("/admin/users?size=100");
  benId = users.content.find((u) => u.email === benEmail)!.id;

  templateId = await publishWorkflow(admin, "Notifications onboarding", [stageDef("onboarding", "Onboarding")]);
  caseId = (await admin.createCase(customerId, templateId, "Notification fixture")).id;
  const roadmap = await admin.get<RoadmapView>(`/cases/${caseId}/roadmap`);
  milestoneId = roadmap.stages[0]!.milestones[0]!.id;

  await request.dispose();
});

test("assigning a task raises the assignee's badge; opening the row lands on the case and clears it", async ({
  page,
  request,
}) => {
  const ann = await Api.as(request, tenant.slug, annEmail);
  await assignTaskToBen(ann, "Draft the statement of work");

  await signIn(page, tenant.slug, benEmail);
  await reloadToDashboard(page);
  const badge = inboxButton(page, /Inbox, 1 unread/);
  await expect(badge).toBeVisible();

  await badge.click();
  await expect(drawer(page)).toBeVisible();
  const row = drawer(page).getByRole("button", { name: /Task assigned to you: Draft the statement of work/ });
  await expect(row).toBeVisible();
  await row.click();

  await expect(page).toHaveURL(new RegExp(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}$`));
  // Positive control above (the badge showed 1); now it is gone.
  await expect(inboxButton(page, "Inbox")).toBeVisible();
  await expect(inboxButton(page, /unread/)).toHaveCount(0);
  await inboxButton(page, "Inbox").click();
  await expect(drawer(page).getByText("0 UNREAD")).toBeVisible();
  await expect(drawer(page).getByRole("button", { name: /Task assigned to you: Draft the statement of work/ })).toBeVisible();
});

test("the keyboard shortcut toggles the drawer and Escape closes it", async ({ page }) => {
  await signIn(page, tenant.slug, benEmail);
  // The shortcut is attached only once the signed-in user (and so the button) has loaded.
  await expect(inboxButton(page, /^Inbox/)).toBeVisible();
  await expect(drawer(page)).toHaveCount(0);

  await page.keyboard.press("ControlOrMeta+j");
  await expect(drawer(page)).toBeVisible();

  await page.keyboard.press("ControlOrMeta+j");
  await expect(drawer(page)).toHaveCount(0);

  await page.keyboard.press("ControlOrMeta+j");
  await expect(drawer(page)).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(drawer(page)).toHaveCount(0);
});

test("turning a type off stops it arriving", async ({ page, request }) => {
  const ann = await Api.as(request, tenant.slug, annEmail);
  await assignTaskToBen(ann, "Assigned before the switch-off");

  await signIn(page, tenant.slug, benEmail);
  await reloadToDashboard(page);
  await expect(inboxButton(page, /Inbox, 1 unread/)).toBeVisible(); // positive control

  await inboxButton(page, /Inbox/).click();
  await drawer(page).getByRole("button", { name: "Preferences" }).click();
  const inApp = drawer(page).getByRole("switch", { name: "In-app for Task assigned to me" });
  const email = drawer(page).getByRole("switch", { name: "Email for Task assigned to me" });
  await expect(inApp).toHaveAttribute("aria-checked", "true");
  await inApp.click();
  await expect(inApp).toHaveAttribute("aria-checked", "false");
  await email.click();
  await expect(email).toHaveAttribute("aria-checked", "false");

  await expectTaskAssignedChannels(request, false, false);
  await assignTaskToBen(ann, "Assigned after the switch-off");

  await reloadToDashboard(page);
  await inboxButton(page, /Inbox/).click();
  await expect(drawer(page).getByRole("button", { name: /Assigned before the switch-off/ })).toBeVisible();
  await expect(drawer(page).getByText(/Assigned after the switch-off/)).toHaveCount(0);
  await expect(drawer(page).getByText("1 UNREAD")).toBeVisible();
});

test("a keyed stage sends a stage-entered alert", async ({ page, request }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/notifications`);
  await page.getByRole("button", { name: "New template" }).click();
  const dialog = page.getByRole("dialog", { name: "New template" });
  await dialog.getByLabel("Key", { exact: true }).fill("kickoff");
  await dialog.getByLabel("Name", { exact: true }).fill("Kickoff alert");
  await dialog.getByLabel("Subject when a stage is entered").fill("Kickoff for {customer} is in {stage}");
  await dialog.getByLabel("Body when a stage is entered").fill("{owner} now owns {case}.");
  await dialog.getByRole("button", { name: "Save", exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole("cell", { name: "Kickoff alert", exact: true })).toBeVisible();

  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const keyedTemplate = await publishWorkflow(admin, "Keyed onboarding", [
    stageDef("intake", "Intake"),
    stageDef("kickoff", "Kickoff", { notificationTemplateKey: "kickoff" }),
  ]);
  const keyedCase = (await admin.createCase(customerId, keyedTemplate, "Keyed fixture")).id;
  const current = await admin.get<{
    name: string;
    owningDepartmentId?: string;
    owningTeamId?: string;
    attributes?: Record<string, string>;
  }>(`/cases/${keyedCase}`);
  await admin.put(`/cases/${keyedCase}`, {
    name: current.name,
    ownerUserId: benId,
    owningDepartmentId: current.owningDepartmentId ?? null,
    owningTeamId: current.owningTeamId ?? null,
    attributes: current.attributes ?? {},
  });

  // Negative control: nothing has entered the keyed stage yet.
  await page.context().clearCookies();
  await signIn(page, tenant.slug, benEmail);
  await inboxButton(page, /Inbox/).click();
  // Wait for the list to load (an earlier row is known to exist) so the negative below means something.
  await expect(drawer(page).getByRole("button", { name: /Assigned before the switch-off/ })).toBeVisible();
  await expect(drawer(page).getByText(/Kickoff for/)).toHaveCount(0);
  await page.keyboard.press("Escape");

  const roadmap = await admin.get<RoadmapView>(`/cases/${keyedCase}/roadmap`);
  const requirement = roadmap.stages[0]!.milestones[0]!.requirements[0]!;
  await admin.post(`/cases/${keyedCase}/requirements/${requirement.id}/satisfy`, {});

  await reloadToDashboard(page);
  await inboxButton(page, /Inbox/).click();
  await expect(
    drawer(page).getByRole("button", { name: new RegExp(`Kickoff for ${CUSTOMER} is in Kickoff`) }),
  ).toBeVisible();
});

test("a daily-digest user receives one digest email", async ({ page, request }) => {
  await signIn(page, tenant.slug, benEmail);
  await inboxButton(page, /Inbox/).click();
  await drawer(page).getByRole("button", { name: "Preferences" }).click();
  // Test 3 switched the type off; turn it back on so the assignment below is mailed at all.
  const inApp = drawer(page).getByRole("switch", { name: "In-app for Task assigned to me" });
  const emailSwitch = drawer(page).getByRole("switch", { name: "Email for Task assigned to me" });
  await inApp.click();
  await expect(inApp).toHaveAttribute("aria-checked", "true");
  await emailSwitch.click();
  await expect(emailSwitch).toHaveAttribute("aria-checked", "true");
  await drawer(page).getByRole("tab", { name: "Daily digest" }).click();
  await expect(drawer(page).getByRole("tab", { name: "Daily digest" })).toHaveAttribute("aria-selected", "true");
  await expectTaskAssignedChannels(request, true, true, "DAILY"); // every queued save, the cadence last, is stored before the task is assigned

  const ann = await Api.as(request, tenant.slug, annEmail);
  await assignTaskToBen(ann, "Digest-worthy task");

  // Shift to the next 08:05 on a working day (the default calendar is UTC, Mon-Fri). A
  // one-second nudge returns the backend's total offset, so this holds whatever earlier
  // specs already moved the clock by.
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const offset = await admin.shiftClock(1);
  const backendNow = Date.now() + offset * 1000;
  // The weekday skip assumes the default tenant calendar (UTC, Mon-Fri, no holidays).
  const target = new Date(backendNow);
  target.setUTCHours(8, 5, 0, 0);
  while (target.getTime() <= backendNow || [0, 6].includes(target.getUTCDay())) {
    target.setUTCDate(target.getUTCDate() + 1);
  }
  const seconds = Math.ceil((target.getTime() - backendNow) / 1000);
  // One call moves at most 60 days; a target is never more than four days away.
  await admin.shiftClock(seconds);

  // runDigest queues the digests AND dispatches this tenant's email (DigestJob.runOne), so the
  // explicit dispatch that follows finds nothing left to send, and a second digest run queues
  // nothing: one digest, not two.
  const digestsBefore = await countEmails(benEmail, "Your daily digest");
  expect(await admin.runDigest()).toBeGreaterThanOrEqual(1);
  expect(await admin.runEmailDispatch()).toBe(0);
  expect(await admin.runDigest()).toBe(0);
  // ben's address is unique to this tenant, but count a delta anyway: exactly one digest left.
  await expect.poll(() => countEmails(benEmail, "Your daily digest")).toBe(digestsBefore + 1);

  const mail = await readEmailBody(benEmail, "Your daily digest");
  expect(mail).toContain("Digest-worthy task");
  expect(mail).toMatch(new RegExp(`http://localhost:3000/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`));
});
