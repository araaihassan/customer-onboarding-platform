import Link from "next/link";
import { ArrowRightIcon } from "@/components/icons";
import { Card, CardHeader } from "@/components/ui/Card";
import { StatusPill } from "@/components/ui/StatusPill";
import { ProgrammeJourneyList } from "./ProgrammeJourneyList";
import { ProgrammeParticipants } from "./ProgrammeParticipants";
import { ProgrammeRollupBar } from "./ProgrammeRollupBar";
import type { ProgrammeDetail as ProgrammeDetailData } from "@/lib/api/programmes";
import { t } from "@/lib/i18n";

/**
 * One programme (design spec §8.1): header with the customer and the
 * duration-weighted rollup, the journeys list with per-journey progress and
 * status, and a participants panel.
 *
 * A pure presentational composition over `ProgrammeDetailView` -- the owning
 * page (`programmes/[id]/page.tsx`) resolves `useProgramme(id)` and its own
 * loading/error/not-found branches; this component only ever renders once a
 * programme is actually in hand, the same split `CustomerDetailPage` draws
 * against its own summary card.
 */
export function ProgrammeDetail({ detail, slug }: { detail: ProgrammeDetailData; slug: string }) {
  const programme = detail.programme;
  const journeys = detail.journeys ?? [];
  const percent = detail.rolledUpProgressPercent ?? 0;
  const journeysCovered = detail.journeysCovered ?? 0;

  if (!programme) return null;

  return (
    <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
      <ProgrammeBackLink slug={slug} />

      <Card>
        <div className="flex items-center flex-wrap" style={{ gap: "var(--ob-space-11)" }}>
          <h2
            className="text-ink min-w-0 truncate"
            style={{
              font: "600 var(--ob-type-section-heading-size)/var(--ob-type-section-heading-line) var(--ob-font-family-ui)",
              letterSpacing: "var(--ob-type-section-heading-tracking)",
            }}
          >
            {programme.name}
          </h2>
          <StatusPill status={programme.status} />
        </div>

        {programme.customerId && (
          <Link
            href={`/t/${slug}/customers/${programme.customerId}`}
            className="inline-block text-text-subtle hover:underline"
            style={{
              marginTop: "var(--ob-space-4)",
              font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
            }}
          >
            {programme.customerName}
          </Link>
        )}

        {programme.description && (
          <p
            className="text-text-muted"
            style={{
              marginTop: "var(--ob-space-11)",
              font: "var(--ob-type-body-size)/var(--ob-type-body-line) var(--ob-font-family-ui)",
              maxWidth: "60ch",
            }}
          >
            {programme.description}
          </p>
        )}

        <div style={{ marginTop: "var(--ob-space-16)" }}>
          <ProgrammeRollupBar percent={percent} journeysCovered={journeysCovered} />
        </div>
      </Card>

      <Card>
        <CardHeader title={t("programme.journeys.title")} count={journeys.length} />
        <ProgrammeJourneyList journeys={journeys} slug={slug} customerId={programme.customerId} />
      </Card>

      <ProgrammeParticipants programmeId={programme.id ?? ""} />
    </section>
  );
}

export function ProgrammeBackLink({ slug }: { slug: string }) {
  return (
    <Link
      href={`/t/${slug}/programmes`}
      className="inline-flex items-center self-start text-text-subtle hover:underline"
      style={{
        gap: "var(--ob-space-6)",
        font: "var(--ob-type-table-cell-size)/var(--ob-type-table-cell-line) var(--ob-font-family-ui)",
      }}
    >
      <span aria-hidden="true" style={{ transform: "rotate(180deg)", display: "inline-flex" }}>
        <ArrowRightIcon size={14} />
      </span>
      {t("programme.detail.back")}
    </Link>
  );
}
