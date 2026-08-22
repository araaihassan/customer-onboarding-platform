"use client";

import Link from "next/link";
import { useParams, usePathname, useRouter, useSearchParams } from "next/navigation";
import { ArrowRightIcon, WorkflowIcon } from "@/components/icons";
import { CaseHeader } from "@/components/journey/CaseHeader";
import { CaseSwitcher } from "@/components/journey/CaseSwitcher";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { Tabs, panelId, type TabItem } from "@/components/ui/Tabs";
import { ApiError } from "@/lib/api/client";
import { useCase, useCases, useRoadmap } from "@/lib/api/cases";
import { useCustomer } from "@/lib/api/customers";
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

      <Tabs items={TABS} value={tab} onChange={setTab} />

      <div role="tabpanel" id={panelId(tab)} aria-labelledby={`tab-${tab}`}>
        {tab === "journey" && <JourneyPreview caseId={caseId} />}
        {tab === "tasks" && <EmptyState title={t("case.tabs.tasks.empty")} />}
        {tab === "documents" && <EmptyState title={t("case.tabs.documents.empty")} />}
        {tab === "agreements" && <EmptyState title={t("case.tabs.agreements.empty")} />}
        {tab === "timeline" && <EmptyState title={t("case.tabs.timeline.empty")} />}
      </div>
    </section>
  );
}

/**
 * A minimal preview of the stage graph -- names and milestone counts, no
 * expand/collapse or requirement detail yet. The interactive milestone rows
 * that replace this are the next task's own job.
 */
function JourneyPreview({ caseId }: { caseId: string }) {
  const roadmap = useRoadmap(caseId);

  if (roadmap.isLoading) return <SkeletonRows rows={5} height={48} />;
  if (roadmap.isError) return <EmptyState title={t("common.error")} />;

  const stages = roadmap.data?.stages ?? [];

  return (
    <ul className="flex flex-col" style={{ gap: "var(--ob-space-8)" }}>
      {stages.map((stage) => (
        <li
          key={stage.id}
          className="bg-bg-surface border border-border-default"
          style={{ borderRadius: "var(--ob-radius-row)", padding: "var(--ob-space-13) var(--ob-space-16)" }}
        >
          <p
            className="text-text-primary"
            style={{ font: "500 var(--ob-type-13-size)/var(--ob-type-13-line) var(--ob-font-family-ui)" }}
          >
            {stage.name}
          </p>
          <p
            className="text-text-muted"
            style={{ font: "var(--ob-type-11-size)/var(--ob-type-11-line) var(--ob-font-family-ui)" }}
          >
            {t("case.journey.milestoneCount", { count: String(stage.milestones?.length ?? 0) })}
          </p>
        </li>
      ))}
    </ul>
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
