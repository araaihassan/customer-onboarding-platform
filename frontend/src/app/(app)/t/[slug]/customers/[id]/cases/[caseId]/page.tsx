"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import { CommentThread } from "@/components/comment/CommentThread";
import { ArrowRightIcon, WorkflowIcon } from "@/components/icons";
import { AwaitingApprovalBanner } from "@/components/journey/AwaitingApprovalBanner";
import { CaseHeader } from "@/components/journey/CaseHeader";
import { CaseSwitcher } from "@/components/journey/CaseSwitcher";
import { DocumentsTab } from "@/components/journey/DocumentsTab";
import { HoldDialog } from "@/components/journey/HoldDialog";
import { PlanTab, type PlanPreviewMilestone } from "@/components/journey/PlanTab";
import { Roadmap } from "@/components/journey/Roadmap";
import { TasksTab } from "@/components/journey/TasksTab";
import { TimelineTab } from "@/components/journey/TimelineTab";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { Tabs, panelId, type TabItem } from "@/components/ui/Tabs";
import { ApiError } from "@/lib/api/client";
import { useApprovals, useCase, useCases, useParticipants, useResume, useRoadmap } from "@/lib/api/cases";
import { useCustomer } from "@/lib/api/customers";
import { usePlanRevisions } from "@/lib/api/plans";
import { useDefinition, useWorkflowTemplate, type Stage } from "@/lib/api/workflows";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The journey workspace (uispecs README §5): header card with the switcher
 * beneath its meta line, then the tab strip. Journey, Tasks and (Task 33)
 * Documents all have real content now; Agreements still arrives with the
 * sub-project that builds it -- a missing tab would read as a missing
 * feature, so it still renders an honest placeholder instead of being
 * hidden.
 */
export default function CaseWorkspacePage() {
  const { slug, id: customerId, caseId } = useParams<{ slug: string; id: string; caseId: string }>();
  const router = useRouter();
  const pathname = usePathname();
  const tab = useSearchParams().get("tab") ?? "journey";

  const customer = useCustomer(customerId);
  const caseQuery = useCase(caseId);
  const cases = useCases(customerId);
  const resume = useResume();
  const canHold = useHasPermission("case.hold");
  const [holding, setHolding] = useState(false);

  // Q21/Q22/Q23: the Plan tab and the held-case banner both only apply to a
  // case pinned to a customer-owned template's version -- neither CaseView
  // nor the roadmap carries that fact, so it is resolved from the template
  // record itself, the same way the version editor page resolves it for
  // gate 1 (`useWorkflowTemplate`'s own doc comment).
  const templateId = caseQuery.data?.templateId ?? "";
  const pinnedVersionId = caseQuery.data?.versionId ?? "";
  const template = useWorkflowTemplate(templateId);
  const isCustomerTemplate = Boolean(template.data?.customerId);
  const definition = useDefinition(isCustomerTemplate ? templateId : "", isCustomerTemplate ? pinnedVersionId : "");
  const planRevisions = usePlanRevisions(isCustomerTemplate ? caseId : "");
  const revisionList = planRevisions.data ?? [];
  const hasApprovedRevision = revisionList.some((r) => r.status === "APPROVED");
  const hasOutstandingRevision = revisionList.some((r) => r.status === "ISSUED");
  const showAwaitingApproval =
    isCustomerTemplate && caseQuery.data?.status === "ON_HOLD" && !hasApprovedRevision;
  const planMilestones = flattenPlanMilestones(definition.data?.stages ?? []);

  const TABS: TabItem[] = [
    { id: "journey", label: t("case.tabs.journey") },
    { id: "tasks", label: t("case.tabs.tasks") },
    { id: "documents", label: t("case.tabs.documents") },
    { id: "agreements", label: t("case.tabs.agreements") },
    ...(isCustomerTemplate ? [{ id: "plan", label: t("case.tabs.plan") }] : []),
    { id: "timeline", label: t("case.tabs.timeline") },
  ];

  useSetPageHeader(customer.data?.displayName ?? "");

  function setTab(id: string) {
    router.replace(`${pathname}?tab=${id}`);
  }

  const notFound = caseQuery.error instanceof ApiError && caseQuery.error.status === 404;

  if (customer.isLoading || caseQuery.isLoading) return <SkeletonRows rows={4} height={64} />;

  if (notFound || !caseQuery.data || !customer.data) {
    return (
      <EmptyState
        icon={<WorkflowIcon size={28} />}
        title={t("common.notFound")}
        action={<BackLink slug={slug} customerId={customerId} />}
      />
    );
  }

  if (caseQuery.isError || customer.isError) {
    return (
      <EmptyState
        icon={<WorkflowIcon size={28} />}
        title={t("common.error")}
        action={
          <Button type="button" variant="secondary" onClick={() => void caseQuery.refetch()}>
            {t("common.retry")}
          </Button>
        }
      />
    );
  }

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <BackLink slug={slug} customerId={customerId} />

      <CaseHeader caseData={caseQuery.data} customer={customer.data} />

      {showAwaitingApproval && (
        <AwaitingApprovalBanner
          caseId={caseId}
          hasOutstandingRevision={hasOutstandingRevision}
          onAction={() => setTab("plan")}
        />
      )}

      {/*
       * SCREENS.md §3's wrapping two-column flex: content `1 1 520px`, aside
       * `1 1 296px` (min 264px, max 340px). `flex-wrap` on the row plus each
       * child's own basis/min/max is what forces the aside beneath the
       * content below ~1100px (the RESPONSIVE table's corrected breakpoint,
       * superseding this section's own inline "~900px") -- no separate media
       * query needed, the shell's fixed rail/padding width is what makes the
       * combined basis first exceed the available row width there.
       */}
      <div className="flex flex-wrap" style={{ gap: "var(--ob-space-16)" }}>
        <div className="flex flex-col min-w-0" style={{ flex: "1 1 520px", gap: "var(--ob-space-16)" }}>
          <Tabs items={TABS} value={tab} onChange={setTab} />

          <div role="tabpanel" id={panelId(tab)} aria-labelledby={`tab-${tab}`}>
            {tab === "journey" && <JourneyPreview caseId={caseId} />}
            {tab === "tasks" && <TasksTab caseId={caseId} />}
            {tab === "documents" && <DocumentsTab caseId={caseId} />}
            {tab === "agreements" && <EmptyState title={t("case.tabs.agreements.empty")} />}
            {tab === "plan" && isCustomerTemplate && <PlanTab caseId={caseId} milestones={planMilestones} />}
            {tab === "timeline" && <TimelineTab caseId={caseId} />}
          </div>
        </div>

        {/* Right rail (SCREENS.md §3): the sibling-journeys switcher and the
            hold/resume action -- the only two right-rail items this sub-project
            actually has real data and functionality for; CUSTOMER/SLA
            CLOCK/PARTICIPANTS cards are later sub-projects' scope. */}
        <aside
          className="flex flex-col"
          style={{
            flex: "1 1 296px",
            minWidth: 264,
            maxWidth: 340,
            gap: "var(--ob-space-16)",
            borderLeft: "1px solid var(--ob-line)",
            paddingLeft: "var(--ob-space-16)",
          }}
        >
          <CaseSwitcher
            cases={cases.data ?? []}
            activeCaseId={caseId}
            slug={slug}
            customerId={customerId}
            canCreate={false}
            onCreateNew={() => {}}
          />

          {canHold && (caseQuery.data.status === "ACTIVE" || caseQuery.data.status === "ON_HOLD") && (
            <div className="flex justify-end">
              {caseQuery.data.status === "ACTIVE" ? (
                <Button type="button" variant="secondary" onClick={() => setHolding(true)}>
                  {t("case.hold.action")}
                </Button>
              ) : (
                <Button type="button" variant="secondary" disabled={resume.isPending} onClick={() => resume.mutate(caseId)}>
                  {t("case.resume.action")}
                </Button>
              )}
            </div>
          )}
        </aside>
      </div>

      {holding && <HoldDialog caseId={caseId} onClose={() => setHolding(false)} />}
    </section>
  );
}

/**
 * The journey tab's real content (Task 27): the roadmap's stage headers and
 * expandable milestone rows, plus (Task 29) the journey's own comment
 * thread below it -- design spec §8.3's "on the journey itself", the
 * `CASE` half of `CommentResourceType`. `participants` and `approvals` are
 * fetched once here and threaded down rather than once per row -- the
 * roadmap can hold dozens of milestones across nine stages, and
 * `CommentThread` reuses this same fetch for its author resolution rather
 * than a second one.
 */
function JourneyPreview({ caseId }: { caseId: string }) {
  const roadmap = useRoadmap(caseId);
  const participants = useParticipants(caseId);
  const approvals = useApprovals(caseId);

  if (roadmap.isLoading) return <SkeletonRows rows={5} height={48} />;
  if (roadmap.isError) return <EmptyState title={t("common.error")} />;

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-20)" }}>
      <Roadmap
        caseId={caseId}
        stages={roadmap.data?.stages ?? []}
        participants={participants.data ?? []}
        approvals={approvals.data ?? []}
      />

      <CommentThread
        caseId={caseId}
        resourceType="CASE"
        resourceId={caseId}
        participants={participants.data ?? []}
      />
    </div>
  );
}

/**
 * The pinned version's own milestone definitions, flattened with a combined
 * `portalVisible` -- `PlanRevisionService.issue`'s own rule snapshots a
 * milestone only when BOTH it and its stage are portal-visible, so a hidden
 * stage's milestones must read as hidden here too, not just the milestone's
 * own flag.
 */
function flattenPlanMilestones(stages: Stage[]): PlanPreviewMilestone[] {
  const milestones: PlanPreviewMilestone[] = [];
  for (const stage of stages) {
    const stageVisible = stage.portalVisible !== false;
    for (const milestone of stage.milestones ?? []) {
      if (!milestone.id) continue;
      milestones.push({
        id: milestone.id,
        name: milestone.name ?? "",
        portalVisible: stageVisible && milestone.portalVisible !== false,
      });
    }
  }
  return milestones;
}

function BackLink({ slug, customerId }: { slug: string; customerId: string }) {
  return (
    <Link
      href={`/t/${slug}/customers/${customerId}`}
      className="inline-flex items-center self-start text-text-muted hover:underline"
      style={{ gap: "var(--ob-space-6)", font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)" }}
    >
      <span aria-hidden="true" style={{ transform: "rotate(180deg)", display: "inline-flex" }}>
        <ArrowRightIcon size={14} />
      </span>
      {t("case.workspace.back")}
    </Link>
  );
}
