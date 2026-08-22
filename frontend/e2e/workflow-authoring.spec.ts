import { expect, test } from "@playwright/test";
import { apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Authoring a workflow through the builder, end to end: a template, three
 * stages, a branch rule, the validation-failure path, and publish.
 *
 * The builder has no UI to declare a workflow attribute (`draftState`'s
 * `addAttribute`/`updateAttribute` exist, but nothing in the page calls
 * them) or to set a stage's entry condition -- both real gaps, recorded in
 * CLAUDE.md. So the branch rule here reads a known CUSTOMER field
 * (`industry`), which the builder can fully author without either; the
 * `segment`-attribute-driven skip belongs to `case-lifecycle.spec.ts`, whose
 * workflow is seeded through the API for exactly that reason.
 */
let tenant: Tenant;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "author");
  await request.dispose();
});

test("authors a workflow through publish, including the validation-failure path", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/admin/workflows`);

  await page.getByRole("button", { name: "New template" }).click();
  const create = page.getByRole("dialog", { name: "New workflow template" });
  await create.getByLabel("Name").fill("Onboarding");
  await create.getByRole("button", { name: "Create template" }).click();

  // Creating a template opens its first draft directly.
  await expect(page.getByRole("heading", { name: "Edit workflow" })).toBeVisible();

  // Stage 1: Registration, one milestone, one requirement.
  await page.getByRole("button", { name: "Add stage" }).click();
  await page.getByLabel("Stage name").fill("Registration");
  await page.getByRole("button", { name: "Add milestone" }).click();
  await page.getByLabel("Milestone name").fill("Sign up");
  await page.getByRole("button", { name: "Add requirement" }).click();
  await page.getByLabel("Requirement label").fill("ID check");

  // Stage 2: Legal Review -- deliberately left with no milestone, for the
  // validation-failure path below.
  await page.getByRole("button", { name: "Add stage" }).click();
  await page.getByLabel("Stage name").fill("Legal Review");

  // Stage 3: Go Live, one milestone, one requirement. The final stage, so
  // publish rule 1 (no entry condition on the last stage) is satisfied by
  // construction -- nothing in this builder can set one anyway.
  await page.getByRole("button", { name: "Add stage" }).click();
  await page.getByLabel("Stage name").fill("Go Live");
  await page.getByRole("button", { name: "Add milestone" }).click();
  await page.getByLabel("Milestone name").fill("Finish onboarding");
  await page.getByRole("button", { name: "Add requirement" }).click();
  await page.getByLabel("Requirement label").fill("Sign off");

  // Back to stage 1 to add a branch rule -- the target dropdown only offers
  // forward stages, so this has to wait until stage 3 exists.
  await page.getByRole("button", { name: /Registration/ }).click();
  await page.getByRole("button", { name: "Add branch rule" }).click();
  await page.getByLabel("Condition source").selectOption("CUSTOMER");
  await page.getByLabel("Field").selectOption("industry");
  await page.getByLabel("Value").fill("Logistics");
  await page.getByLabel("Target stage").selectOption({ label: "Go Live" });

  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("button", { name: "Publish version" })).toBeEnabled();

  // The validation-failure path: Legal Review has no milestone yet.
  await page.getByRole("button", { name: "Publish version" }).click();
  const problems = page.getByRole("alert").filter({ hasText: "This version cannot be published yet" });
  await expect(problems).toContainText("Stage Legal Review has no milestone");

  // Fix it.
  await page.getByRole("button", { name: /Legal Review/ }).click();
  await page.getByRole("button", { name: "Add milestone" }).click();
  await page.getByLabel("Milestone name").fill("Review contract");
  await page.getByRole("button", { name: "Add requirement" }).click();
  await page.getByLabel("Requirement label").fill("Legal sign-off");

  await page.getByRole("button", { name: "Save draft" }).click();
  await expect(page.getByRole("button", { name: "Publish version" })).toBeEnabled();
  await page.getByRole("button", { name: "Publish version" }).click();

  await expect(page.getByText("v1 (frozen)")).toBeVisible();
});
