import { expect, test } from "@playwright/test";
import type { Locator, Page } from "@playwright/test";
import { Api, apiContext, provisionTenant, seedUser, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Task 35: the first live proof of the visibility arc `document.view`'s own
 * two independent narrowing mechanisms produce together --
 * `scoping.DocumentDescriptor`'s record-level SCOPE (a document inherits
 * DEPARTMENT/TEAM from its case) and `scoping.DocumentAudienceFilter`'s
 * targeting/sharing AUDIENCE (binds even an ALL-scoped reader) -- through a
 * real browser rather than either mechanism's own already-green unit tests.
 *
 * **Why this seeds two departments and two customers, not one of each --
 * read `DocumentService.visibilitySummary`'s own javadoc before changing
 * this fixture.** The two mechanisms compose by AND, not by union, and they
 * disclose very differently: an audience-excluded document (targeted at a
 * department the reader is not in) is invisible to BOTH the `visible` and
 * the scope-ignoring `total` queries alike, because the audience predicate
 * is baked into both -- so it contributes NOTHING to the hidden-count line,
 * by design (leaking "a document exists, targeted at a department you are
 * not in" would defeat the whole point of targeting). Only a SCOPE-excluded
 * document (one whose owning case the reader's department does not own) can
 * ever move that count off zero. Proving the hidden-count line actually
 * means something therefore needs a document excluded by SCOPE
 * (`legalMemo`, on a Legal-owned case, never targeted at anyone) alongside
 * the one excluded by AUDIENCE (`legalTargeted`, targeted at Legal but living
 * on the Finance-owned case) -- collapsing this fixture to a single
 * department/customer would make every assertion below pass by accident
 * against a hidden count that is vacuously always zero, the same class of
 * always-green, proves-nothing test CLAUDE.md's own "at least one write test
 * must run at the narrowest scope" convention exists to rule out.
 *
 * Seeded entirely through the API (`Api.uploadDocument`/`shareDocument`,
 * Task 35's own additions to `support/tenant.ts`, the multipart-upload
 * equivalent of `lib/api/documents.ts`'s `useUploadDocument`); only the
 * Finance reader's own view is driven through the browser.
 */
let tenant: Tenant;
let financeEmail: string;
let legalEmail: string;
let financeDeptId: string;
let legalDeptId: string;
let untargeted: { id: string; name: string };
let legalTargeted: { id: string; name: string };
let legalMemo: { id: string; name: string };

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "documents");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const finance = await admin.createDepartment("Finance");
  financeDeptId = finance.id;
  const legal = await admin.createDepartment("Legal");
  legalDeptId = legal.id;

  const { templateId } = await admin.publishMinimalWorkflow("Docs Fixture");

  // Owned by Finance -- both a company-wide (untargeted) document and one
  // targeted at Legal live here, so the audience filter, not case ownership,
  // is what decides between them for a Finance reader.
  const financeCustomer = await admin.createCustomer("Northbridge Finance", financeDeptId);
  const financeCase = await admin.createCase(financeCustomer.id, templateId, "Finance Onboarding");

  // Owned by Legal, and never targeted at anyone -- SCOPE alone is what
  // hides this one from a DEPARTMENT-scoped Finance reader, the contrast
  // this fixture needs against legalTargeted's AUDIENCE-based hiding.
  const legalCustomer = await admin.createCustomer("Northbridge Legal Affairs", legalDeptId);
  const legalCase = await admin.createCase(legalCustomer.id, templateId, "Legal Onboarding");

  untargeted = await admin.uploadDocument(financeCase.id, { name: "Company handbook.txt" });
  legalTargeted = await admin.uploadDocument(financeCase.id, {
    name: "Legal NDA draft.txt",
    targetDepartmentId: legalDeptId,
  });
  legalMemo = await admin.uploadDocument(legalCase.id, { name: "Legal team memo.txt" });

  // DEPARTMENT-scoped, a real (not ALL-scoped) reader -- see this file's own
  // top comment for why ALL scope would make the hidden-count half of this
  // spec vacuous.
  financeEmail = await seedUser(request, admin, tenant, "finance", { "document.view": "DEPARTMENT" }, financeDeptId);

  // Legal alone can share legalTargeted -- DocumentAudienceFilter narrows
  // document.share exactly like document.view, ALL-scoped holders included
  // (that class's own doc comment names this share() call specifically), so
  // an ALL-scoped Administrator could not do this share itself.
  //
  // `workflow.view` is also required, for a reason that has nothing to do
  // with sharing itself: `DocumentSharingService.applyWriteScope` resolves
  // the case's current `Stage` (to check `write_scope`) through
  // `AuthorizedQuery` under `workflow.view`, not `document.share` -- the same
  // "viewing a case's full representation is gated by more than case.view"
  // trap CLAUDE.md already documents for `CaseService`'s own
  // `currentStageName` lookup, now confirmed live for a document write path
  // too. Found live: `legalActor.shareDocument` 404'd with no department/
  // audience explanation until this was added -- see this task's own commit
  // body and report for the full trace.
  // The share itself is NOT performed here -- it has to happen inside the
  // test body, between the "before" and "after" assertions, or there would
  // be no "before" state left to observe at all. This was found live: the
  // first run of this spec did the share in `beforeAll`, so by the time the
  // Finance reader's browser ever loaded the page, `legalTargeted` was
  // ALREADY shared -- `expect(rowNamed(page, legalTargeted.name)).toHaveCount(0)`
  // failed with "Received: 1" because the "before" half of the arc had
  // already happened by the time it was checked. A spec-ordering bug, not a
  // product one -- the product was behaving exactly as designed.
  legalEmail = await seedUser(
    request,
    admin,
    tenant,
    "legal",
    { "document.share": "ALL", "workflow.view": "ALL" },
    legalDeptId,
  );

  await request.dispose();
});

/** Scoped to the table view (`data-view="table"`) only -- both it and the <900px card view are always mounted (`DataTable`'s own doc comment), so an unscoped text query would hit a Playwright strict-mode violation at this spec's 1440px default viewport, where only the table is actually visible. */
function tableRows(page: Page): Locator {
  return page.locator('[data-view="table"] [role="row"]');
}

function rowNamed(page: Page, name: string): Locator {
  return tableRows(page).filter({ hasText: name });
}

test("a DEPARTMENT-scoped reader sees an untargeted document, not one targeted at another department, until it is explicitly shared -- and the hidden-count line tracks exactly the difference SCOPE (not AUDIENCE) accounts for", async ({
  page,
  request,
}) => {
  await signIn(page, tenant.slug, financeEmail);
  await page.goto(`/t/${tenant.slug}/documents`);

  await expect(page.locator('[data-view="table"]')).toBeVisible();
  await expect(rowNamed(page, untargeted.name)).toBeVisible();
  await expect(rowNamed(page, legalTargeted.name)).toHaveCount(0);
  // Present in the tenant (on a Legal-owned case), correctly still absent
  // here -- but see below for what it does contribute to.
  await expect(rowNamed(page, legalMemo.name)).toHaveCount(0);

  // visible = { untargeted } = 1. total (scope ignored, audience still
  // applied) = { untargeted, legalMemo } = 2 -- legalTargeted is excluded
  // from BOTH by audience, so it contributes nothing; legalMemo is excluded
  // from `visible` by SCOPE alone (Finance does not own the Legal case) and
  // reappears in `total`, which is the whole reason this line exists.
  await expect(page.getByText("01 VISIBLE · 01 HIDDEN BY SCOPE")).toBeVisible();

  // Now share it, as the Legal reader -- Finance had no way to do this
  // themselves (see this file's top comment). Reloading is the only way an
  // already-open SPA page would ever observe someone else's write; nothing
  // here depends on a live refetch.
  const legalActor = await Api.as(request, tenant.slug, legalEmail);
  await legalActor.shareDocument(legalTargeted.id, "DEPARTMENT", financeDeptId);
  await page.reload();

  await expect(rowNamed(page, untargeted.name)).toBeVisible();
  await expect(rowNamed(page, legalTargeted.name)).toBeVisible();
  await expect(rowNamed(page, legalMemo.name)).toHaveCount(0);

  // visible = { untargeted, legalTargeted } = 2. total = { untargeted,
  // legalTargeted, legalMemo } = 3. legalMemo's own SCOPE exclusion is
  // completely unaffected by a share that only ever widens AUDIENCE -- the
  // hidden count drops to zero only if the excluded document itself
  // changes, never as a side effect of an unrelated share.
  await expect(page.getByText("02 VISIBLE · 01 HIDDEN BY SCOPE")).toBeVisible();
});
