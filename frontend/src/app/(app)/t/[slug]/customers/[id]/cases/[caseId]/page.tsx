"use client";

import { useState } from "react";
import Link from "next/link";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowRightIcon, WorkflowIcon } from "@/components/icons";
import { CaseHeader } from "@/components/journey/CaseHeader";
import { CaseSwitcher } from "@/components/journey/CaseSwitcher";
import { HoldDialog } from "@/components/journey/HoldDialog";
import { Roadmap } from "@/components/journey/Roadmap";
import { TimelineTab } from "@/components/journey/TimelineTab";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { Tabs, panelId, type TabItem } from "@/components/ui/Tabs";
import { ApiError } from "@/lib/api/client";
import { useApprovals, useCase, useCases, useParticipants, useResume, useRoadmap } from "@/lib/api/cases";
import { useCustomer } from "@/lib/api/customers";
import { useHasPermission } from "@/lib/auth/useHasPermission";
import { t } from "@/lib/i18n";

/**
 * The journey workspace (uispecs README §5): header card with the switcher
 * beneath its meta line, then the tab strip. Only the Journey tab has real
 * content yet -- Tasks/Documents/Agreements and a proper Timeline arrive with
 * the sub-projects that build those features; a missing tab would read as a
 * missing feature, so each renders an honest placeholder instead of being
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

  const TABS: TabItem[] = [
    { id: "journey", label: t("case.tabs.journey") },
    { id: "tasks", label: t("case.tabs.tasks") },
    { id: "documents", label: t("case.tabs.documents") },
    { id: "agreements", label: t("case.tabs.agreements") },
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
    <section className="flex flex-col" style={{ gap: "var(--ob-grid-gap)" }}>
      <BackLink slug={slug} customerId={customerId} />

      <CaseHeader caseData={caseQuery.data} customer={customer.data} />

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

      <Tabs items={TABS} value={tab} onChange={setTab} />

      <div role="tabpanel" id={panelId(tab)} aria-labelledby={`tab-${tab}`}>
        {tab === "journey" && <JourneyPreview caseId={caseId} />}
        {tab === "tasks" && <EmptyState title={t("case.tabs.tasks.empty")} />}
        {tab === "documents" && <EmptyState title={t("case.tabs.documents.empty")} />}
        {tab === "agreements" && <EmptyState title={t("case.tabs.agreements.empty")} />}
        {tab === "timeline" && <TimelineTab caseId={caseId} />}
      </div>

      {holding && <HoldDialog caseId={caseId} onClose={() => setHolding(false)} />}
    </section>
  );
}

/**
 * The journey tab's real content (Task 27): the roadmap's stage headers and
 * expandable milestone rows. `participants` and `approvals` are fetched
 * once here and threaded down rather than once per row -- the roadmap can
 * hold dozens of milestones across nine stages.
 */
function JourneyPreview({ caseId }: { caseId: string }) {
  const roadmap = useRoadmap(caseId);
  const participants = useParticipants(caseId);
  const approvals = useApprovals(caseId);

  if (roadmap.isLoading) return <SkeletonRows rows={5} height={48} />;
  if (roadmap.isError) return <EmptyState title={t("common.error")} />;

  return (
    <Roadmap
      caseId={caseId}
      stages={roadmap.data?.stages ?? []}
      participants={participants.data ?? []}
      approvals={approvals.data ?? []}
    />
  );
}

function BackLink({ slug, customerId }: { slug: string; customerId: string }) {
  return (
    <Link
      href={`/t/${slug}/customers/${customerId}`}
      className="inline-flex items-center self-start text-text-muted hover:underline"
      style={{ gap: "var(--ob-space-6)", font: "var(--ob-type-12-5-size)/var(--ob-type-12-5-line) var(--ob-font-family-ui)" }}
    >
      <span aria-hidden="true" style={{ transform: "rotate(180deg)", display: "inline-flex" }}>
        <ArrowRightIcon size={14} />
      </span>
      {t("case.workspace.back")}
    </Link>
  );
}
