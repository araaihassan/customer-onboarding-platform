import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";
import { Api, apiContext, provisionTenant, signIn } from "./support/tenant";
import type { Tenant } from "./support/tenant";

/**
 * Task 36: the first live proof of "request -> fulfil -> review -> satisfy"
 * (design spec 5.3) -- a DOCUMENT requirement with `requiresReview: true`
 * auto-instantiates a `document_request` the instant a case opens, fulfilling
 * it alone must NOT satisfy the requirement, and only an APPROVED review does.
 *
 * **Two real, pre-existing gaps had to close before this spec could even be
 * written -- both confirmed directly against the code, not assumed from the
 * brief's own wording:**
 *
 * 1. `WorkflowDefinitionRequest.RequirementRequest` had no `requiresReview`
 *    field at all, by ANY authoring path (not just the builder UI the brief's
 *    own text names) -- `WorkflowService.newRequirement` never set it either.
 *    There was no way to create the `requiresReview=true` scenario this whole
 *    spec exists to exercise. Closed by threading a boxed `Boolean
 *    requiresReview` through `RequirementRequest`/`RequirementView` and both
 *    of `WorkflowService`'s conversion directions (`toRequirementView`,
 *    `toRequirementRequest`), per `RequirementDefinition.requiresReview`'s own
 *    doc comment, which named this exact gap and its fix in advance.
 * 2. `DocumentRequestController` had exactly three POST mappings and no way to
 *    list a case's document requests over HTTP at all -- `DocumentInstantiation
 *    .instantiateForCase` runs fire-and-forget inside `CaseService.create`'s
 *    own transaction and returns nothing to any caller, so there was no way to
 *    discover the auto-instantiated request's id before calling `fulfil` on
 *    it. Closed by `DocumentRequestService.forCase` (resolving `caseId`
 *    through `AuthorizedQuery` under `document.request` first, then reading
 *    through `AuthorizedQuery.findAll` -- never the pre-existing
 *    `DocumentRequestRepository.findByCaseId`, a narrowly-scoped exclusion for
 *    `DocumentInstantiation` alone) and a matching
 *    `GET /cases/{caseId}/document-requests` mapping. `lib/api/documents.ts`'s
 *    `useDocumentRequests(caseId)` already targeted this exact path, written
 *    in anticipation of the endpoint arriving -- it needed no new code, only a
 *    fix to its own return type, which had guessed a bare array where the real
 *    endpoint returns a `Page`.
 *
 * The workflow is seeded through the raw API `PUT` (createDraftVersion/
 * saveDraft/publishVersion), the same shape `case-lifecycle.spec.ts` and
 * `tasks.spec.ts` already establish for a graph the builder UI cannot author
 * -- here, `requiresReview` itself, which (per this plan's own §5.3 note) has
 * no builder affordance yet even after gap 1 above closes it for the raw API.
 *
 * `fulfil` and `review` are both driven through the API, not the browser:
 * neither `RequestDocumentDialog` nor Task 34's own `ReviewDialog` is mounted
 * anywhere a real user could reach `fulfil`/`review` from today (that task's
 * own deliberate ruling for `ReviewDialog`) -- consistent with that decision,
 * not a shortcut around a hidden UI affordance. Only the FINAL confirmation --
 * the milestone showing complete -- is driven through a real browser, since
 * that is the part an actual user would see and the part worth proving
 * end-to-end rather than through another API read.
 */
let tenant: Tenant;
let customerId: string;
let templateId: string;

interface RoadmapRequirement {
  id: string;
  status: string;
}
interface RoadmapMilestone {
  id: string;
  name: string;
  status: string;
  requirements: RoadmapRequirement[];
}
interface RoadmapStage {
  milestones: RoadmapMilestone[];
}
interface RoadmapResponse {
  stages: RoadmapStage[];
}

interface DocumentRequestView {
  id: string;
  caseId: string;
  requirementId: string | null;
  category: string;
  requiresReview: boolean;
  status: string;
  fulfilledDocumentId: string | null;
}
interface DocumentRequestPage {
  content: DocumentRequestView[];
}

test.beforeAll(async ({ playwright }) => {
  const request = await apiContext(playwright);
  tenant = await provisionTenant(request, "docreq");
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);

  const customer = await admin.createCustomer("Meridian Capital");
  customerId = customer.id;

  const { id } = await admin.createWorkflowTemplate("Document Review Onboarding");
  templateId = id;
  const { versionId, lockVersion } = await admin.createDraftVersion(templateId);
  await admin.saveDraft(templateId, versionId, {
    stages: [
      {
        key: "onboarding",
        name: "Onboarding",
        // Explicit, not left to a default -- CLAUDE.md's own documented trap:
        // an omitted boolean binds to Java's primitive `false`, not the
        // `true` a builder-authored stage would carry.
        autoAdvance: true,
        milestones: [
          {
            key: "compliance",
            name: "Compliance",
            estimatedDurationDays: 1,
            dependsOnMilestoneKeys: [],
            requirements: [
              {
                kind: "DOCUMENT",
                label: "Signed NDA",
                weight: 1,
                mandatory: true,
                documentCategory: "NDA",
                // Gap 1 above -- the whole reason this spec exists. Only
                // authorable at all as of this task.
                requiresReview: true,
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

  await request.dispose();
});

function milestoneRow(page: Page, milestoneName: string) {
  return page.getByTestId("milestone-row").filter({ hasText: milestoneName });
}

test("a DOCUMENT requirement with requiresReview stays open through fulfil, and only satisfies -- completing its milestone, live in the browser -- once the review is approved", async ({
  page,
  request,
}) => {
  const admin = await Api.as(request, tenant.slug, tenant.adminEmail);
  const { id: caseId } = await admin.createCase(customerId, templateId, "Document Review Fixture");

  // Gap 2 above -- the request DocumentInstantiation auto-created the
  // instant this case opened, discoverable for the first time over HTTP.
  const requestsAfterCreate = await admin.get<DocumentRequestPage>(`/cases/${caseId}/document-requests`);
  expect(requestsAfterCreate.content).toHaveLength(1);
  const documentRequest = requestsAfterCreate.content[0]!;
  expect(documentRequest.requirementId).toBeTruthy();
  expect(documentRequest.requiresReview).toBe(true);
  expect(documentRequest.category).toBe("NDA");
  expect(documentRequest.status).toBe("OPEN");

  const roadmapAfterCreate = await admin.get<RoadmapResponse>(`/cases/${caseId}/roadmap`);
  const milestoneAfterCreate = roadmapAfterCreate.stages[0]!.milestones.find((m) => m.name === "Compliance")!;
  expect(milestoneAfterCreate.status).not.toBe("DONE");
  const requirementId = milestoneAfterCreate.requirements[0]!.id;
  expect(milestoneAfterCreate.requirements[0]!.status).toBe("OPEN");

  // Upload a document and fulfil the request with it -- category OTHER
  // (uploadDocument's own default), deliberately not NDA: ContentSniffGuard's
  // per-category allowlist restricts NDA to real office-document bytes
  // (PDF/DOC/DOCX), and this fixture's plain-text upload would 400 under
  // that category. Fulfilling never checks the uploaded document's own
  // category against the request's -- only that it belongs to the same case
  // and is not retired -- so this is a fixture-content choice, not a
  // weakening of what is under test.
  const uploaded = await admin.uploadDocument(caseId, { name: "Signed NDA.txt" });
  const fulfilled = await admin.post<{ status: string; fulfilledDocumentId: string }>(
    `/document-requests/${documentRequest.id}/fulfil`,
    { documentId: uploaded.id },
    200,
  );
  expect(fulfilled.status).toBe("FULFILLED");
  expect(fulfilled.fulfilledDocumentId).toBe(uploaded.id);

  // requiresReview is true -- design spec 5.3: fulfilling alone must NOT
  // satisfy the requirement. The requirement stays OPEN and the milestone
  // stays un-DONE until a review actually approves it.
  const roadmapAfterFulfil = await admin.get<RoadmapResponse>(`/cases/${caseId}/roadmap`);
  const milestoneAfterFulfil = roadmapAfterFulfil.stages[0]!.milestones.find((m) => m.name === "Compliance")!;
  expect(milestoneAfterFulfil.requirements.find((r) => r.id === requirementId)!.status).toBe("OPEN");
  expect(milestoneAfterFulfil.status).not.toBe("DONE");

  // Approve the review -- Task 34's own ReviewDialog is deliberately not
  // mounted anywhere in the UI yet (that task's own ruling), so the API is
  // the only real surface for this action today, consistent with that
  // decision rather than a shortcut around it. DocumentReviewService.review
  // discovers every FULFILLED DocumentRequest this document fulfilled and
  // satisfies its linked requirement.
  await admin.post(`/documents/${uploaded.id}/versions/1/review`, { decision: "APPROVED" }, 200);

  const roadmapAfterReview = await admin.get<RoadmapResponse>(`/cases/${caseId}/roadmap`);
  const milestoneAfterReview = roadmapAfterReview.stages[0]!.milestones.find((m) => m.name === "Compliance")!;
  expect(milestoneAfterReview.requirements.find((r) => r.id === requirementId)!.status).toBe("SATISFIED");
  expect(milestoneAfterReview.status).toBe("DONE");

  // Confirm live, in the browser -- the part a real user would actually see,
  // and the part worth proving end-to-end rather than through another API read.
  await signIn(page, tenant.slug, tenant.adminEmail);
  await page.goto(`/t/${tenant.slug}/customers/${customerId}/cases/${caseId}`);
  await expect(milestoneRow(page, "Compliance").getByText("Done", { exact: true })).toBeVisible();
});
