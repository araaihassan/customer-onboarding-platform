"use client";

import { useState } from "react";
import { useParams } from "next/navigation";
import { DocumentTable } from "@/components/documents/DocumentTable";
import { HiddenCountLine } from "@/components/documents/HiddenCountLine";
import { ScopeFilterRow, type ScopeFilterValue } from "@/components/documents/ScopeFilterRow";
import { VisibilityAside } from "@/components/documents/VisibilityAside";
import { FileTextIcon } from "@/components/icons";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { Pagination } from "@/components/ui/Pagination";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { useDocumentVisibilitySummary, useDocuments, type DocumentVisibilityTier } from "@/lib/api/documents";
import { t } from "@/lib/i18n";

/**
 * The tenant-wide `docs` index (`SCREENS.md` §7) -- closes the gap Task 32's
 * own Ruling 5 deferred (`.superpowers/sdd/2026-09-12-documents/progress.md`,
 * "Task 35: pre-dispatch research"): the route did not exist and nothing
 * linked to it. Composes pieces already built and already reviewed --
 * `ScopeFilterRow`/`HiddenCountLine` (Task 32), `DocumentTable` (Task 31),
 * `VisibilityAside` (Task 33) -- this file adds no new visual component of
 * its own.
 *
 * **Design decision, not in `SCREENS.md` §7:** this screen has no Upload
 * action. The spec's own §7 section names exactly three pieces -- the scope
 * filter row, the table, and the right aside -- and no upload affordance;
 * `UploadDialog` (Task 33) is, structurally, a per-case control (its
 * mutation posts to `POST /cases/{caseId}/documents`, and it takes a
 * required `caseId` prop), and no case-picker component exists anywhere in
 * this codebase to drive one from a tenant-wide screen -- not even task
 * creation, itself case-scoped only (`TasksTab`'s own "My work" board has no
 * cross-case create either). Building one would be new, unspecified feature
 * work, not the "smallest correct wiring" this task calls for. Upload
 * already has a real, working home: the case workspace's own Documents tab
 * (`DocumentsTab.tsx`), where `caseId` is naturally in scope. This index
 * stays a read + filter + visibility-explainer screen, exactly as designed.
 */
export default function DocumentsPage() {
  const { slug } = useParams<{ slug: string }>();

  const [tier, setTier] = useState<ScopeFilterValue>("ALL");
  const [page, setPage] = useState(0);

  const filterTier: DocumentVisibilityTier | undefined =
    tier === "ALL" || tier === "OPEN_REQUESTS" ? undefined : tier;

  const documents = useDocuments(filterTier, page);
  const summary = useDocumentVisibilitySummary(filterTier);

  useSetPageHeader(t("documents.list.title"));

  function changeFilter(next: "ALL" | DocumentVisibilityTier) {
    setTier(next);
    setPage(0);
  }

  const rows = documents.data?.content ?? [];
  const totalPages = documents.data?.totalPages ?? 0;

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <h2 className="sr-only">{t("documents.list.title")}</h2>

      <div
        className="flex flex-wrap items-center"
        style={{ gap: "var(--ob-space-11)" }}
      >
        <ScopeFilterRow active={tier} onChange={changeFilter} />
        <div className="flex-1" />
        <HiddenCountLine visible={summary.data?.visible ?? 0} hidden={summary.data?.hidden ?? 0} />
      </div>

      {/* SCREENS.md §7's own two-column layout: table content, then a fixed
          288px right aside -- the same wrapping-flex shape the case
          workspace's own content/rail split already uses
          (customers/[id]/cases/[caseId]/page.tsx), so this screen collapses
          the aside beneath the table on a narrow viewport the same way. */}
      <div className="flex flex-wrap" style={{ gap: "var(--ob-space-16)" }}>
        <div className="flex flex-col min-w-0" style={{ flex: "1 1 520px", gap: "var(--ob-space-16)" }}>
          {documents.isLoading ? (
            <SkeletonRows rows={6} height={56} />
          ) : documents.isError ? (
            <EmptyState
              icon={<FileTextIcon size={28} />}
              title={t("common.error")}
              action={
                <Button type="button" variant="secondary" onClick={() => void documents.refetch()}>
                  {t("common.retry")}
                </Button>
              }
            />
          ) : (
            <>
              <DocumentTable documents={rows} slug={slug} />
              {totalPages > 1 && (
                <Pagination
                  label={t("documents.page.nav")}
                  page={page}
                  totalPages={totalPages}
                  onChange={setPage}
                  disabled={documents.isFetching}
                />
              )}
            </>
          )}
        </div>

        <aside style={{ flex: "1 1 264px", maxWidth: 288 }}>
          <VisibilityAside />
        </aside>
      </div>
    </section>
  );
}
