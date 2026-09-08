import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Task 31: the first live proof that Tasks 11-30's backend and frontend
 * actually work together through a real browser -- an ad-hoc task created
 * through the Tasks tab, a task assigned, a requirement-linked task
 * completed and its milestone watched to advance, a requirement-linked task
 * cancelled and its milestone watched NOT to advance, and a comment posted
 * to a task's thread.
 *
 * The workflow is seeded through the API (createDraftVersion/saveDraft/
 * publishVersion), the same shape case-lifecycle.spec.ts already
 * established, with a requirement of kind TASK on two independent
 * milestones in a single stage: TaskInstantiation (co.ara.onboarding.task)
 * creates a real Task row for each the instant the case opens, with
 * requirementId already set -- that is what "watch the milestone advance"
 * actually exercises, not a UI affordance this suite invents.
 *
 * ADAPTATION FROM THE BRIEF, confirmed by reading the code directly before
 * writing this spec: there is no UI anywhere in this codebase to assign a
 * task to someone. `TaskDetail.tsx` only ever DISPLAYS
 * `task.assigneeId ? shortId(task.assigneeId) : "Unassigned"` -- the sole
 * `<select>` in that file is the status control, not an assignee picker.
 * Tasks 27 and 28's own reports both independently flagged this exact gap.
 * Building that picker is new product scope, not this task's job, so "assign
 * it" is adapted to: seed the assignment with a direct API call
 * (`PUT /tasks/{taskId}`, a full replace per UpdateTaskRequest's own doc
 * comment -- every field the request accepts is supplied, not just
 * assigneeId, using the values the create response itself returned), then
 * verify through the UI that the resulting task genuinely displays as
 * assigned in TaskDetail, not "Unassigned".
 */
let tenant: Tenant;
let customerId: string;
let templateId: string;
let colleagueUserId: string;

interface RoadmapMilestone {
  id: string;
  name: string;
}
interface RoadmapStage {
  milestones: RoadmapMilestone[];
}
interface RoadmapResponse {
  stages: RoadmapStage[];
}

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "tasks");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer("Riverside Logistics");
  customerId = customer.id;

  const { id } = await admin.createWorkflowTemplate("Task Onboarding");
  templateId = id;
  const { versionId, lockVersion } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "onboarding",
        name: "Onboarding",
        autoAdvance: true,
        milestones: [
          {
            key: "kickoff",
            name: "Kickoff",
            estimatedDurationDays: 1,
            dependsOnMilestoneKeys: [],
            // kind: TASK -- the whole reason sub-project 3's backend exists:
            // TaskInstantiation creates a real Task row for this the moment
            // the case opens, with requirementId already set.
            requirements: [{ kind: "TASK", label: "Kickoff task", mandatory: true }],
          },
          {
            key: "verification",
            name: "Verification",
            estimatedDurationDays: 1,
            // Independent of Kickoff (no dependsOnMilestoneKeys), so it is
            // ACTIVE from case creation too -- CaseEngine's status
            // computation gates ACTIVE on reachability and unmet
            // dependencies alone, never on a sibling milestone's own state.
            dependsOnMilestoneKeys: [],
            requirements: [{ kind: "TASK", label: "Verification task", mandatory: true }],
          },
        ],
        branchRules: [],
      },
    ],
    attributes: [],
    lockVersion,
  });
  await admin.publishVersion(templateId, versionId);

  // Exists purely as an assignment target -- never signed in as, so it needs
  // no role or activation. resolveAssigneeId (TaskService) only requires the
  // CALLER to hold user.view; Administrator holds it at ALL.
  const colleague = await admin.createUser(`colleague@${tenant.slug}.test`, "Colleague Person");
  colleagueUserId = colleague.id;

  await request.dispose();
});

async function createCase(page: Page, name: string): Promise<string> {
  await page.goto(`/t/${tenant.slug}/customers/${customerId}`);
  await page.getByRole("button", { name: "New case" }).click();

  const dialog = page.getByRole("dialog", { name: "New case" });
  await dialog.getByLabel("Case name").fill(name);
  await dialog.getByRole("radio", { name: "Task Onboarding" }).click();
  await dialog.getByRole("button", { name: "Create case" }).click();

  await page.waitForURL(/\/cases\/[0-9a-f-]+$/);
  return page.url().split("/cases/")[1]!;
}

function milestoneRow(page: Page, milestoneName: string) {
  return page.getByTestId("milestone-row").filter({ hasText: milestoneName });
}

function taskCard(page: Page, title: string) {
  return page.getByTestId("task-card").filter({ hasText: title });
}

async function openTasksTab(page: Page) {
  await page.getByRole("tab", { name: "Tasks" }).click();
}

async function openJourneyTab(page: Page) {
  await page.getByRole("tab", { name: "Journey" }).click();
}

test("an ad-hoc task can be created through the Tasks tab", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await createCase(page, "Ad-hoc Task Fixture");

  await openTasksTab(page);
  // Both requirement-instantiated tasks (Kickoff task, Verification task)
  // already exist -- this is not the empty state.
  await expect(taskCard(page, "Kickoff task")).toBeVisible();

  await page.getByRole("button", { name: "New task" }).click();
  const dialog = page.getByRole("dialog", { name: "New task" });
  await dialog.getByLabel("Title").fill("Send welcome packet");
  await dialog.getByRole("button", { name: "Create task" }).click();

  await expect(dialog).toBeHidden();
  await expect(taskCard(page, "Send welcome packet")).toBeVisible();
});

test("a task assigned through the API (no picker exists in the UI) displays its assignee, not Unassigned", async ({
  page,
  request,
}) => {
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const apiCaseId = (
    await admin.createCase(customerId, templateId, "Assignment Fixture")
  ).id;

  const roadmap = await admin.get<RoadmapResponse>(`/cases/${apiCaseId}/roadmap`);
  const kickoffMilestoneId = roadmap.stages[0]!.milestones.find((m) => m.name === "Kickoff")!.id;

  const created = await admin.post<{
    id: string;
    title: string;
    description?: string;
    priority: string;
    milestoneId: string;
    dueDate?: string;
  }>(
    `/cases/${apiCaseId}/tasks`,
    { milestoneId: kickoffMilestoneId, title: "Chase signed contract", priority: "MEDIUM" },
    201,
  );

  // UpdateTaskRequest is a full replace (CLAUDE.md's own invariant): every
  // field it accepts is supplied here, using exactly what create returned,
  // not just the one field this test cares about.
  await admin.put(`/tasks/${created.id}`, {
    title: created.title,
    description: created.description,
    priority: created.priority,
    milestoneId: created.milestoneId,
    dueDate: created.dueDate,
    assigneeId: colleagueUserId,
  });

  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${apiCaseId}?tab=tasks`);

  await taskCard(page, "Chase signed contract").click();
  const dialog = page.getByRole("dialog", { name: "Chase signed contract" });
  await expect(dialog.getByText("Unassigned")).toHaveCount(0);
  // shortId is the last dash-segment of the UUID (lib/api/customers.ts).
  await expect(dialog.getByText(colleagueUserId.split("-").pop()!)).toBeVisible();
});

test("completing a requirement-linked task advances its milestone to Done, and a comment posts to its thread", async ({
  page,
}) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await createCase(page, "Completion Fixture");

  await openJourneyTab(page);
  await expect(milestoneRow(page, "Kickoff").getByText("Active", { exact: true })).toBeVisible();

  await openTasksTab(page);
  await taskCard(page, "Kickoff task").click();
  const dialog = page.getByRole("dialog", { name: "Kickoff task" });

  await dialog.getByLabel("Status").selectOption("COMPLETED");
  await dialog.getByRole("button", { name: "Change status" }).click();
  // .first(): the status <select> still carries a "Completed" <option> node
  // in the DOM after selection, matching the same text -- the StatusPill
  // span is the one that renders first (the dialog's own title block, above
  // the status control section).
  await expect(dialog.getByText("Completed", { exact: true }).first()).toBeVisible();

  // Task 17's chain, now proven through a real browser: TaskService
  // .changeStatus -> RequirementService.satisfy -> CaseEngine.reconcile.
  await dialog.getByLabel("Add a comment").fill("Confirmed complete with the client.");
  await dialog.getByRole("button", { name: "Post comment" }).click();
  await expect(dialog.getByText("Confirmed complete with the client.")).toBeVisible();

  // TaskDetail's own Dialog carries no Cancel/close button (only
  // CreateTaskDialog's does) -- Escape is the only way out.
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  await openJourneyTab(page);
  await expect(milestoneRow(page, "Kickoff").getByText("Done", { exact: true })).toBeVisible();
  // Its independent sibling is untouched by this -- CaseEngine gates DONE on
  // this milestone's own requirements alone, never a sibling's.
  await expect(milestoneRow(page, "Verification").getByText("Active", { exact: true })).toBeVisible();
});

test("cancelling a requirement-linked task with a reason leaves its milestone unadvanced", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await createCase(page, "Cancellation Fixture");

  await openTasksTab(page);
  await taskCard(page, "Verification task").click();
  const dialog = page.getByRole("dialog", { name: "Verification task" });

  await dialog.getByLabel("Status").selectOption("CANCELLED");
  await dialog.getByLabel("Cancellation reason").fill("Superseded by a manual check with the customer.");
  await dialog.getByRole("button", { name: "Change status" }).click();
  // .first(): see the identical note in the completion test above.
  await expect(dialog.getByText("Cancelled", { exact: true }).first()).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();

  await openJourneyTab(page);
  // Task 18's own backend invariant: cancelling never calls satisfy, so the
  // requirement stays open and the milestone never reaches Done.
  await expect(milestoneRow(page, "Verification").getByText("Active", { exact: true })).toBeVisible();
  await expect(milestoneRow(page, "Verification").getByText("Done", { exact: true })).toHaveCount(0);
});
