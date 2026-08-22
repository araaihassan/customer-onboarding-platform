"use client";

import { useState } from "react";
import { Pagination } from "@/components/ui/Pagination";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { TimelineRow } from "@/components/ui/TimelineRow";
import { useTimeline } from "@/lib/api/cases";
import { t } from "@/lib/i18n";

/**
 * The immutable audit list (uispecs §5e). Paginates rather than truncating
 * silently -- a hard cap with no way to reach the rest would read as a
 * complete history when it is not. `meta` carries the raw action key
 * (`milestone.force_completed`, `case.hold`, …) since `summary` is already
 * the human-readable prose the backend records; there is no separate actor
 * name on `AuditEventView` to resolve.
 */
export function TimelineTab({ caseId }: { caseId: string }) {
  const [page, setPage] = useState(0);
  const timeline = useTimeline(caseId, page);

  if (timeline.isLoading) return <SkeletonRows rows={5} height={44} />;
  if (timeline.isError) return <EmptyState title={t("common.error")} />;

  const events = timeline.data?.content ?? [];
  const totalElements = timeline.data?.totalElements ?? 0;
  const totalPages = timeline.data?.totalPages ?? 0;

  if (totalElements === 0) {
    return <EmptyState title={t("case.timeline.emptyTitle")} description={t("case.timeline.emptyDescription")} />;
  }

  return (
    <div className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
      <p
        className="text-text-faint"
        style={{
          font: "500 var(--ob-type-10-size)/var(--ob-type-10-line) var(--ob-font-family-data)",
          textTransform: "uppercase",
          letterSpacing: "0.08em",
        }}
      >
        {t("case.timeline.header", { count: String(totalElements) })}
      </p>

      <ul className="flex flex-col" style={{ gap: "var(--ob-space-11)" }}>
        {events.map((event) => (
          <TimelineRow
            key={event.id}
            timestamp={formatTimestamp(event.occurredAt)}
            summary={event.summary ?? ""}
            meta={event.action}
          />
        ))}
      </ul>

      {totalPages > 1 && (
        <Pagination
          label={t("case.timeline.pages")}
          page={page}
          totalPages={totalPages}
          onChange={setPage}
          disabled={timeline.isFetching}
        />
      )}
    </div>
  );
}

/** UTC ISO in, "YYYY-MM-DD HH:MM" out -- a slice, not a Date(), so no local-timezone shift moves an event onto the wrong day (CaseHeader's own toDateOnly note). */
function formatTimestamp(iso?: string): string {
  return iso ? iso.slice(0, 16).replace("T", " ") : "—";
}
