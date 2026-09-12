import { expect, test } from "@playwright/test";
import { activate, Api, apiContext, PASSWORD, provisionTenant, readEmailToken, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Sub-project 3A's second and final e2e spec (Q20's non-negotiable, proved live
 * rather than only at the service layer -- `programme.ProgrammeScopeTest` is
 * the backend version of case two below).
 *
 * Both programmes and their journeys are seeded through the API in
 * `beforeAll`, the same convention `migration.spec.ts` and
 * `customer-plan.spec.ts` already established: there is no UI to create a
 * programme or link a journey into one (Task 29 built only the read side --
 * index, detail, participants), so seeding is the only way to get one onto
 * screen at all. This file's whole point is proving what the READ side does
 * with a programme once one exists, not proving a create flow that has no UI.
 *
 * Case one is the straightforward happy path: an administrator (Administrator
 * template, every permission at ALL) opens a programme holding two journeys
 * and reads the duration-weighted rollup's "across 2 journeys" coverage line.
 *
 * Case two is the one that matters. A hand-built role holds `programme.view`
 * and `case.view` at ASSIGNED ONLY -- no DEPARTMENT/TEAM/ALL grant to fall
 * back on, the same deliberate choice `ProgrammeScopeTest` itself makes, so a
 * passing test proves the participation-mediated filter specifically, not a
 * wider grant masking it. `customer.view` and `workflow.view` are both ALL:
 * neither is what this test is about (the first resolves the programme's own
 * `customerName`, the second is `CaseService.get`'s own dependency on the
 * current stage's name -- a role missing it 404s the WHOLE case read, not a
 * blank field, per this repository's own recorded finding). The user is a
 * `CaseParticipant` on exactly one of the programme's two journeys, and a
 * *programme* participant on both by virtue of the programme itself -- the
 * shape that would leak the second journey if participation, not a real
 * per-case grant, were what governed access.
 */
let tenant: Tenant;
let customerId: string;
let templateId: string;

// Case one: happy path.
let rollupProgrammeId: string;
const journeyAlphaName = "Rollup Journey Alpha";
const journeyBetaName = "Rollup Journey Beta";

// Case two: the scope filter.
let scopeProgrammeId: string;
let visibleCaseId: string;
let invisibleCaseId: string;
const visibleJourneyName = "Scope Visible Journey";
const invisibleJourneyName = "Scope Invisible Journey";
let participantEmail: string;

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "programme");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer("Programme Customer");
  customerId = customer.id;

  const workflow = await admin.publishMinimalWorkflow("Programme Onboarding");
  templateId = workflow.templateId;

  // --- Case one: a programme with two journeys, both visible to the admin ---
  const rollupProgramme = await admin.createProgramme("Rollup Programme", customerId);
  rollupProgrammeId = rollupProgramme.id;

  const journeyAlpha = await admin.createCase(customerId, templateId, journeyAlphaName);
  const journeyBeta = await admin.createCase(customerId, templateId, journeyBetaName);
  await admin.addProgrammeJourney(rollupProgrammeId, journeyAlpha.id);
  await admin.addProgrammeJourney(rollupProgrammeId, journeyBeta.id);

  // --- Case two: a programme with one journey the participant can open and one they cannot ---
  const scopeProgramme = await admin.createProgramme("Scope Test Programme", customerId);
  scopeProgrammeId = scopeProgramme.id;

  const visibleCase = await admin.createCase(customerId, templateId, visibleJourneyName);
  visibleCaseId = visibleCase.id;
  const invisibleCase = await admin.createCase(customerId, templateId, invisibleJourneyName);
  invisibleCaseId = invisibleCase.id;
  await admin.addProgrammeJourney(scopeProgrammeId, visibleCaseId);
  await admin.addProgrammeJourney(scopeProgrammeId, invisibleCaseId);

  // A hand-built role, not one of the twelve seeded templates -- ASSIGNED ONLY on
  // programme.view/case.view, so a pass proves the participation-mediated read
  // specifically, not a wider grant (DEPARTMENT/TEAM/ALL) masking it. Built up
  // manually rather than through the shared `seedUser` helper because this test
  // needs the user's id back (to add them as a real CaseParticipant on `visible`),
  // and `seedUser` only ever returns the email.
  participantEmail = `sponsor@${tenant.slug}.test`;
  const { id: participantUserId } = await admin.createUser(participantEmail, "Sponsor");
  const { id: roleId } = await admin.createRole("sponsor-role", {
    "programme.view": "ASSIGNED",
    "case.view": "ASSIGNED",
    "customer.view": "ALL",
    "workflow.view": "ALL",
  });
  await admin.assignRole(participantUserId, roleId);
  await activate(request, tenant.slug, await readEmailToken(participantEmail), PASSWORD);

  // A real, independent claim to case.view on `visible` alone -- not manufactured
  // by programme membership. Never touches `invisible`.
  await admin.addCaseParticipant(visibleCaseId, participantUserId, "PARTICIPANT");

  // Programme participation on BOTH journeys (alsoGrantJourneyAccess left false,
  // the default) -- exactly the shape that would leak `invisible` if
  // participation itself governed journey access rather than the real
  // CaseParticipant row above.
  await admin.addProgrammeParticipant(scopeProgrammeId, participantUserId, "PARTICIPANT");

  await request.dispose();
});

test("an administrator reads a programme's duration-weighted rollup across both its journeys", async ({ page }) => {
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/programmes/${rollupProgrammeId}`);

  await expect(page.getByRole("heading", { level: 2, name: "Rollup Programme" })).toBeVisible();
  await expect(page.getByText("across 2 journeys")).toBeVisible();

  await expect(page.getByRole("link", { name: journeyAlphaName })).toBeVisible();
  await expect(page.getByRole("link", { name: journeyBetaName })).toBeVisible();
});

test("a programme participant sees only the journey they are a real case participant on, and a direct link to the other 404s", async ({
  page,
}) => {
  await signIn(page, tenant.slug, participantEmail);
  await page.goto(`/t/${tenant.slug}/programmes/${scopeProgrammeId}`);

  await expect(page.getByRole("heading", { level: 2, name: "Scope Test Programme" })).toBeVisible();

  // The container is readable at all -- programme.view ASSIGNED resolved through
  // the participant's own programme membership.
  await expect(page.getByRole("link", { name: visibleJourneyName })).toBeVisible();

  // The rollup is computed over the ONE journey this reader can actually open --
  // never the programme's real total of two.
  await expect(page.getByText("across 1 journeys")).toBeVisible();

  // The invisible journey never appears in the list at all -- not a placeholder
  // row, not a name with no link, nothing. Programme participation on its own
  // grants nothing beyond the container.
  await expect(page.getByText(invisibleJourneyName)).toHaveCount(0);

  // And opening it directly is a real 404, not a blank or partial case workspace --
  // the same contrast ProgrammeScopeTest's own
  // theInvisibleJourneyIs404NotAnEmptyFieldWhenOpenedDirectly draws at the service
  // layer.
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${invisibleCaseId}`);
  await expect(page.getByText("Not found")).toBeVisible();
});
