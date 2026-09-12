"use client";

import { useParams } from "next/navigation";
import { LayersIcon } from "@/components/icons";
import { ProgrammeBackLink, ProgrammeDetail } from "@/components/programme/ProgrammeDetail";
import { useSetPageHeader } from "@/components/shell/PageHeader";
import { Button } from "@/components/ui/Button";
import { EmptyState, SkeletonRows } from "@/components/ui/States";
import { ApiError } from "@/lib/api/client";
import { useProgramme } from "@/lib/api/programmes";
import { t } from "@/lib/i18n";

/**
 * One programme (design spec §8.1, `/t/{slug}/programmes/[id]`).
 *
 * The shell owns the <h1> and takes the programme's name from the page header
 * context, so everything here starts at <h2> -- `ProgrammeDetail`'s own
 * heading. `ProgrammeDetailView` from Task 28's `useProgramme(id)` carries the
 * programme, its visible journeys and the duration-weighted rollup in one
 * read, gated `programme.view` server-side; a cross-tenant or out-of-scope id
 * is a 404, identically to an id that never existed (spec invariant).
 */
export default function ProgrammeDetailPage() {
  const { slug, id } = useParams<{ slug: string; id: string }>();

  const { data, isLoading, error, refetch } = useProgramme(id);

  const notFound = error instanceof ApiError && error.status === 404;

  useSetPageHeader(data?.programme?.name ?? "", data?.programme?.customerName);

  if (isLoading) return <SkeletonRows rows={5} height={56} />;

  /**
   * A server error is not a missing record, and must not be reported as one --
   * same distinction `CustomerDetailPage` draws for its own read.
   */
  if (error && !notFound) {
    return (
      <section>
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("common.error")}
          action={
            <Button type="button" variant="secondary" onClick={() => void refetch()}>
              {t("common.retry")}
            </Button>
          }
        />
      </section>
    );
  }

  /**
   * Out-of-scope and non-existent are the same 404 by design (spec invariant:
   * "a cross-tenant id is consistently a 404"). Saying anything about access
   * here would hand back exactly the fact the 404 exists to withhold.
   */
  if (notFound || !data?.programme) {
    return (
      <section className="flex flex-col" style={{ gap: "var(--ob-space-16)" }}>
        <ProgrammeBackLink slug={slug} />
        <EmptyState
          icon={<LayersIcon size={28} />}
          title={t("common.notFound")}
          description={t("programme.detail.notFoundHint")}
        />
      </section>
    );
  }

  return <ProgrammeDetail detail={data} slug={slug} />;
}
